;; Copyright (c) Cognitect, Inc. All rights reserved.

(ns cognitect.rebl.impl.file
  (:import java.io.File)
  (:require [clojure.java.io :as io]
            [clojure.core.protocols :as p]
            [clojure.datafy :as datafy]))

(defn datafy-node
  [^File f]
  (with-meta
    {}
    {:rebl.file/path (.getCanonicalPath f)
     :rebl.file/modified (.lastModified f)}))

(comment
  (defmacro time-tap
    [context & body]
    `(let [start# (System/currentTimeMillis)
           result# (do
                     ~@body)]
       (tap> (assoc ~context :msec (- (System/currentTimeMillis) start#)))
       result#)))

(defn bounded-slurp
  "Like slurp, but only up to approximately limit chars."
  [f limit]
  (let [sw (java.io.StringWriter.)]
    (with-open [rdr (io/reader f)]
      (let [^"[C" buffer (make-array Character/TYPE 8192)]
        (loop [n 0]
          (when (< n limit)
            (let [size (.read rdr buffer)]
              (when (pos? size)
                (do
                  (.write sw buffer 0 size)
                  (recur (+ n size)))))))))
    (.toString sw)))

(defn datafy-file
  [^File f]
  (let [m (datafy-node f)]
    (assoc m
      :length (.length f)
      :name (.getName f))))

(defn datafy-directory
  [^File f]
  (let [m (datafy-node f)]
    (with-meta (map (fn [^File f] (.getName f)) (.listFiles f))
      {`p/nav (fn [_ k v]
                (io/file f v))})))

(extend-protocol p/Datafiable
  java.io.File
  (datafy [f]
          (cond
           (.isFile f) (datafy-file f)
           (.isDirectory f) (datafy-directory f)
           :default f)))

(defn datafied-file?
  [x]
  (let [m (meta x)]
    (and (= 'java.io.File (::datafy/class m))
         (:length x))))


