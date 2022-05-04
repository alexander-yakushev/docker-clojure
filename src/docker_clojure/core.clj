(ns docker-clojure.core
  (:require
    [clojure.java.shell :refer [sh with-sh-dir]]
    [clojure.math.combinatorics :as combo]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [docker-clojure.config :as cfg]
    [docker-clojure.dockerfile :as df]
    [docker-clojure.manifest :as manifest]
    [docker-clojure.util :refer [get-or-default default-docker-tag
                                 full-docker-tag]]))

(defn contains-every-key-value?
  "Returns true if the map `haystack` contains every key-value pair in the map
  `needles`. `haystack` may contain additional keys that are not in `needles`.
  Returns false if any of the keys in `needles` are missing from `haystack` or
  have different values."
  [haystack needles]
  (every? (fn [[k v]]
            (= v (get haystack k)))
          needles))

(defn base-image-tag [base-image jdk-version distro]
  (str base-image ":" jdk-version
       (case base-image
         "eclipse-temurin" "-jdk-"
         "-")
       (name distro)))

(defn exclude?
  "Returns true if `variant` matches one of `exclusions` elements (meaning
  `(contains-every-key-value? variant exclusion)` returns true)."
  [exclusions variant]
  (some (partial contains-every-key-value? variant) exclusions))

(s/def ::variant
  (s/keys :req-un [::cfg/jdk-version ::cfg/base-image ::cfg/base-image-tag
                   ::cfg/distro ::cfg/build-tool ::cfg/build-tool-version
                   ::cfg/maintainer ::cfg/docker-tag]
          :opt-un [::cfg/build-tool-versions ::cfg/architectures]))

(defn assoc-if [m pred k v]
  (if (pred)
    (assoc m k v)
    m))

(defn variant-map [[base-image jdk-version distro
                    [build-tool build-tool-version]]]
  (let [variant-arch (get cfg/distro-architectures
                          (-> distro namespace keyword))
        base {:jdk-version        jdk-version
              :base-image         base-image
              :base-image-tag     (base-image-tag base-image jdk-version distro)
              :distro             distro
              :build-tool         build-tool
              :build-tool-version build-tool-version
              :maintainer         (str/join " & " cfg/maintainers)}]
    (-> base
        (assoc :docker-tag (default-docker-tag base))
        (assoc-if #(nil? (:build-tool-version base)) :build-tool-versions
                  cfg/build-tools)
        (assoc-if #(seq variant-arch) :architectures variant-arch))))

(defn pull-image [image]
  (sh "docker" "pull" image))

(defn generate-dockerfile! [installer-hashes variant]
  (let [build-dir (df/build-dir variant)
        filename  "Dockerfile"]
    (println "Generating" (str build-dir "/" filename))
    (df/write-file build-dir filename installer-hashes variant)
    (assoc variant
      :build-dir build-dir
      :dockerfile filename)))

(defn build-image [installer-hashes {:keys [docker-tag base-image] :as variant}]
  (let [image-tag (str "clojure:" docker-tag)
        _         (println "Pulling base image" base-image)
        _         (pull-image base-image)

        {:keys [dockerfile build-dir]}
        (generate-dockerfile! installer-hashes variant)

        build-cmd (remove nil? ["docker" "build" "--no-cache" "-t" image-tag
                                "--load" "-f" dockerfile "."])]
    (apply println "Running" build-cmd)
    (let [{:keys [out err exit]}
          (with-sh-dir build-dir (apply sh build-cmd))]
      (if (zero? exit)
        (println "Succeeded")
        (do
          (println "ERROR:" err)
          (print out)))))
  (println))

(def latest-variant
  "The latest variant is special because we include all 3 build tools via the
  [::all] value on the end."
  (list (:default cfg/base-images)
        cfg/default-jdk-version
        (get-or-default cfg/default-distros cfg/default-jdk-version)
        [::all]))

(defn image-variant-combinations [base-images jdk-versions distros build-tools]
  (reduce
    (fn [variants jdk-version]
      (concat
        variants
        (let [base-image (get-or-default base-images jdk-version)]
          (combo/cartesian-product #{(get-or-default base-images jdk-version)}
                                   #{jdk-version}
                                   (get-or-default distros base-image)
                                   build-tools))))
    #{} jdk-versions))

(defn image-variants [base-images jdk-versions distros build-tools]
  (into #{}
        (comp
          (map variant-map)
          (remove #(= ::s/invalid (s/conform ::variant %))))
        (conj
          (image-variant-combinations base-images jdk-versions distros
                                      build-tools)
          latest-variant)))

(defn build-images [installer-hashes variants]
  (println "Building images")
  (doseq [variant variants]
    (build-image installer-hashes variant)))

(defn generate-dockerfiles! [installer-hashes variants]
  (doseq [variant variants]
    (generate-dockerfile! installer-hashes variant)))

(defn valid-variants []
  (remove (partial exclude? cfg/exclusions)
          (image-variants cfg/base-images cfg/jdk-versions cfg/distros
                          cfg/build-tools)))

(defn generate-manifest! [variants]
  (let [git-head (->> ["git" "rev-parse" "HEAD"] (apply sh) :out)
        manifest (manifest/generate {:maintainers cfg/maintainers
                                     :architectures cfg/default-architectures
                                     :git-repo cfg/git-repo}
                                    git-head variants)]
    (println "Writing manifest of" (count variants) "variants to clojure.manifest...")
    (spit "clojure.manifest" manifest)))

(defn sort-variants
  [variants]
  (sort
    (fn [v1 v2]
      (cond
        (= "latest" (:docker-tag v1)) -1
        (= "latest" (:docker-tag v2)) 1
        :else (let [c (compare (:jdk-version v1) (:jdk-version v2))]
                (if (not= c 0)
                  c
                  (let [c (compare (full-docker-tag v1) (full-docker-tag v2))]
                    (if (not= c 0)
                      c
                      (throw
                        (ex-info "No two variants should have the same full Docker tag"
                                 {:v1 v1, :v2 v2}))))))))
    variants))

(defn -main [& args]
  (case (first args)
    "clean" (df/clean-all)
    "dockerfiles" (generate-dockerfiles! cfg/installer-hashes (valid-variants))
    "manifest" (-> (valid-variants) sort-variants generate-manifest!)
    (build-images cfg/installer-hashes (valid-variants)))
  (System/exit 0))
