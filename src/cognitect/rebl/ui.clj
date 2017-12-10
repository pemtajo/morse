;;   Copyright (c) Cognitect, Inc. All rights reserved.

(ns cognitect.rebl.ui
  (:require
   [cognitect.rebl :as rebl]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.spec.alpha :as s]
   [clojure.core.async :as async :refer [<!! chan tap untap]])
  (:import [javafx.fxml FXMLLoader]
           [javafx.scene Scene]
           [javafx.event EventHandler]
           [javafx.application Platform]
           [javafx.collections FXCollections]
           [javafx.scene.input KeyEvent KeyCodeCombination KeyCode KeyCombination$Modifier]
           [javafx.scene.control.cell MapValueFactory]
           [javafx.scene.control TableView TableColumn]
           [javafx.util Callback]
           [javafx.beans.property ReadOnlyObjectWrapper]))

(defn set-code [code-view code]
  (-> (.getEngine code-view)
      (.executeScript "document.cm")
      (.call "setValue" (to-array [(binding [pp/*print-right-margin* 80]
                                     (with-out-str (pp/pprint code)))]))))

(defn get-code [code-view]
  (-> (.getEngine code-view)
      (.executeScript "document.cm")
      (.call "getValue" (to-array []))))

(defn edn-viewer [val]
  (let [wv (javafx.scene.web.WebView.)
        eng (.getEngine wv)]
    (-> eng .getLoadWorker .stateProperty
        (.addListener
         (reify javafx.beans.value.ChangeListener
                (changed [_ ob oldv newv]
                         (when (= newv javafx.concurrent.Worker$State/SUCCEEDED)
                           (set-code wv val))))))
    (.load eng (str (io/resource "codeview.html")))
    wv))

(defn table-column
  "returns a TableColumn with given name and CellValueFactory callback
  based on f, a fn of the row"
  [name f]
  (doto (TableColumn. name)
    (.setCellValueFactory (reify Callback
                                 (call [_ cdf]
                                       #_(f (.getValue cdf))
                                       (ReadOnlyObjectWrapper. (f (.getValue cdf))))))))

(defn add-selection-listener
  "adds a selection listener to table given f, a fn of the index and
  the row value. returns table"
  [table f]
  (-> table .getSelectionModel .selectedIndexProperty
      (.addListener (reify javafx.beans.value.ChangeListener
                           (changed [_ ob oldidx nidx]
                                    (when (not= nidx -1)
                                      (f nidx (-> table .getItems (.get nidx))))))))
  table)

(defn map-vb
  ([amap] (map-vb amap nil))
  ([amap val-cb]
     (let [t (TableView. (FXCollections/observableList (vec amap)))]
       (-> t .getColumns (.setAll [(table-column "key" key) (table-column "val" val)]))
       (when val-cb
         (add-selection-listener t (fn [idx [k v]] (val-cb k v)))
         (-> t .getSelectionModel .selectFirst))
       t)))

(def Map? #(instance? java.util.Map %1))

(defn finitify
  "Turn a list into a finite indexed collection"
  [coll]
  (if (vector? coll)
    coll
    (into [] (take (or *print-length* 100000) coll))))

;; making an explicit collection of pairs so we have a row index
;; in hand, otherwise we get into silliness overriding concrete
;; TableCell to recover it later.
;; See https://stackoverflow.com/a/43102706/1456939
(defn coll-vb
  ([alist] (coll-vb alist nil))
  ([alist val-cb]
     (let [t (TableView. (FXCollections/observableList (into [] (map-indexed vector) (finitify alist))))]
       (-> t .getColumns (.setAll [(table-column "idx" first) (table-column "val" second)]))
       (when val-cb
         (add-selection-listener t (fn [idx [k v]] (val-cb idx v)))
         (-> t .getSelectionModel .selectFirst))
       t)))

(def Coll? #(instance? java.util.Collection %1))

(swap! rebl/registry update-in [:viewers]
       assoc
       :rebl/edn {:pred #'any? :ctor #'edn-viewer}
       :rebl/map {:pred #'Map? :ctor #'map-vb}
       :rebl/coll {:pred #'Coll? :ctor #'coll-vb})

(swap! rebl/registry update-in [:browsers]
       assoc
       :rebl/map {:pred Map? :ctor #'map-vb}
       :rebl/coll {:pred #'Coll? :ctor #'coll-vb})

(defn viewer-for
  "returns {:keys [view-ui view-options view-choice]}"
  [val]
  (let [{:keys [viewers pref]} (rebl/viewers-for val)]
    {:view-ui ((-> viewers pref :ctor) val)
     ;;incorporate :id for choice control
     :view-options (mapv (fn [[k v]] (assoc v :id k)) viewers)
     :view-choice (-> viewers pref (assoc :id pref))}))

(defn update-choice [control options choice]
  (-> control (.setItems (FXCollections/observableList options)))
  (-> control (.setValue choice)))

(defn update-pane [pane ui]
  (-> pane .getChildren (.setAll [ui])))

(defn view [{:keys [state view-pane viewer-choice fwd-button] :as ui} path-seg val]
  (let [viewer (viewer-for val)]
    (swap! state merge (assoc viewer :path-seg path-seg :view-val val))
    (update-choice viewer-choice (:view-options viewer) (:view-choice viewer))
    (update-pane view-pane (:view-ui viewer))
    (.setDisable fwd-button (-> val rebl/browsers-for :browsers empty?))))

(defn browser-for
  "returns {:keys [browse-ui browse-options browse-choice]}"
  [ui val]
  (let [{:keys [browsers pref]} (rebl/browsers-for val)]
    {:browse-ui ((-> browsers pref :ctor) val (partial view ui))
     ;;incorporate :id for choice control
     :browse-options (mapv (fn [[k v]] (assoc v :id k)) browsers)
     :browse-choice (-> browsers pref (assoc :id pref))}))

(defn browse [{:keys [state browse-pane browser-choice] :as ui} val]
  (let [browser (browser-for ui val)]
    (swap! state merge (assoc browser :browse-val val))
    (update-choice browser-choice (:browse-options browser) (:browse-choice browser))
    (update-pane browse-pane (:browse-ui browser))))

(defn rtz [{:keys [state state-history eval-table eval-history
                   browse-pane browser-choice code-view root-button back-button] :as ui}]
  (reset! state-history ())
  (let [ehist @eval-history
        val (-> ehist first :val)
        bc {:id :rebl/eval-history}
        browser {:browse-ui eval-table
                 :browse-options [bc]
                 :browse-choice bc}]
    (-> eval-table .getItems (.setAll ehist))
    (swap! state merge browser)
    (update-choice browser-choice [bc] bc)
    (update-pane browse-pane eval-table)
    (set-code code-view (-> ehist first :expr))
    (.setDisable root-button true)
    (.setDisable back-button true)
    (-> eval-table .getSelectionModel .selectFirst)))

(defn expr-loop [{:keys [exprs eval-history follow-editor-check] :as ui}]
  (binding [*file* "user/rebl.clj"]
    (in-ns 'user)
    (apply require clojure.main/repl-requires)
    (loop []
      (let [msg (<!! exprs)]
        (when msg
          (let [eval? (contains? msg :eval)
                msg (if eval?
                      {:expr (:eval msg)
                       :val (try (eval (:eval msg))
                                 (catch Throwable ex
                                   (Throwable->map ex)))}
                      msg)]
            (when (or eval? (.isSelected follow-editor-check))
                  (swap! eval-history conj msg)
                  (Platform/runLater #(rtz ui)))
            (recur)))))))

(defonce ^:private ui-count (atom 0))

(defn eval-pressed [{:keys [code-view exprs] :as ui}]
  (let [code (get-code code-view)
        form (read-string code)]
    (async/put! exprs {:eval form})))

(defn fwd-pressed [{:keys [state state-history root-button back-button] :as ui}]
  (let [statev @state]
    (swap! state-history conj statev)
    (browse ui (:view-val statev))
    (.setDisable root-button false)
    (.setDisable back-button false)))

(defn back-pressed [{:keys [state state-history root-button back-button fwd-button
                            browse-pane view-pane browser-choice viewer-choice] :as ui}]
  (let [[[ostate] nhist] (swap-vals! state-history pop)]
    (prn :ostate ostate)
    (reset! state ostate)
    (update-pane browse-pane (:browse-ui ostate))
    (update-pane view-pane (:view-ui ostate))
    (update-choice browser-choice (:browse-options ostate) (:browse-choice ostate))
    (update-choice viewer-choice (:view-options ostate) (:view-choice ostate))
    (.setDisable root-button (empty? nhist))
    (.setDisable back-button (empty? nhist))
    (.setDisable fwd-button (-> (:view-val ostate) rebl/browsers-for :browsers empty?))))

(defn wire-handlers [{:keys [root-button back-button fwd-button eval-button
                             scene eval-table code-view browse-pane view-pane] :as ui}]
  (let [wire-button (fn [f b]
                      (.setOnAction b (reify EventHandler (handle [_ e] (f)))))
        wire-key (fn [f k & cs]
                   (.addEventFilter scene KeyEvent/KEY_PRESSED
                                    (let [kc (KeyCodeCombination. k (into-array KeyCombination$Modifier cs))]
                                      (reify EventHandler
                                             (handle [_ e]
                                               (when (.match kc e)
                                                 (.consume e)
                                                 (f)))))))]
    (wire-key #(eval-pressed ui) KeyCode/ENTER KeyCodeCombination/CONTROL_DOWN)
    (wire-key #(.requestFocus browse-pane) KeyCode/B KeyCodeCombination/CONTROL_DOWN)
    (wire-key #(.requestFocus view-pane) KeyCode/V KeyCodeCombination/CONTROL_DOWN)
    (wire-key #(.requestFocus code-view) KeyCode/C KeyCodeCombination/CONTROL_DOWN)
    (wire-button #(eval-pressed ui) eval-button)
    (wire-button #(fwd-pressed ui) fwd-button)
    (wire-button #(back-pressed ui) back-button)
    (wire-button #(rtz ui) root-button)
    ;;this handling is special and not like other browsers
    (add-selection-listener eval-table (fn [idx row]
                                         (let [{:keys [expr val]} row]
                                           (set-code code-view expr)
                                           (view ui idx val))))))

(defn- init [{:keys [exprs-mult]}]
  (Platform/runLater
   #(try (let [loader (FXMLLoader. (io/resource "rebl.fxml"))
               root (.load loader)               
               names (.getNamespace loader)
               node (fn [id] (.get names id))
               scene (Scene. root 1200 800)
               stage (javafx.stage.Stage.)
               _ (.setScene stage scene)
               exprs (chan 10)
               vc (proxy [javafx.util.StringConverter] []
                    (toString [v] (-> v :id str)))
               ui {:scene scene
                   :stage stage
                   :exprs exprs
                   :state (atom {})
                   :state-history (atom ())
                   :eval-history (atom ())
                   :eval-table (doto (node "evalTable")
                                 (.setItems (FXCollections/observableList (java.util.ArrayList.))))
                   :expr-column (doto (node "exprColumn")
                                  (.setCellValueFactory (MapValueFactory. :expr)))
                   :val-column (doto (node "valColumn")
                                 (.setCellValueFactory (MapValueFactory. :val)))
                   :code-view (doto (node "codeView")
                                #_(.setZoom 1.2))
                   :follow-editor-check (node "followEditorCheck")
                   :eval-button (node "evalButton")
                   :browser-choice (doto (node "browserChoice")
                                     (.setConverter vc))
                   :browse-pane (node "browsePane")
                   :def-button (node "defButton")
                   :viewer-choice (doto (node "viewerChoice")
                                    (.setConverter vc))
                   :view-pane (node "viewPane")
                   :root-button (doto (node "rootButton")
                                  (.setDisable true))
                   :back-button (doto (node "backButton")
                                  (.setDisable true))
                   :fwd-button (doto (node "fwdButton")
                                 (.setDisable true))}]
           (-> scene .getStylesheets (.add (str (io/resource "fx.css"))))
           (.setTitle stage (str "REBL " (swap! ui-count inc)))
           (.show stage)
           (-> (:code-view ui) .getEngine (.load (str (io/resource "codeview.html"))))
           (wire-handlers ui)
           
           (tap exprs-mult exprs)
           (async/thread (clojure.main/with-bindings (expr-loop ui))))
         (catch Throwable ex
           (println ex)))))

(defn create [argmap]
  (Platform/setImplicitExit false)
  (init argmap)
  nil)
