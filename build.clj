(ns build
  "Release jar for Clojars: the sources under src/ (cljs plus the clj macros)
  and a pom. The version comes from the caller, normally the git tag."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.sstoehrm/hammer)
(def class-dir "target/classes")

(defn- jar-file [version] (format "target/hammer-%s.jar" version))

(defn- version! [{:keys [version]}]
  (let [v (some-> version str (clojure.string/replace #"^v" ""))]
    (when-not (and v (re-matches #"\d+\.\d+\.\d+(-[\w.]+)?" v))
      (throw (ex-info (str "build: :version must look like 0.1.0, got " (pr-str version)) {})))
    v))

(defn jar
  "Builds target/hammer-<version>.jar with its pom."
  [opts]
  (let [version (version! opts)
        basis (b/create-basis {:project "deps.edn"})]
    (b/delete {:path class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :pom-data [[:description "Fast ClojureScript UI: re-frame-style events, db-path components, Canvas 2D and WebGL2 draw components"]
                             [:url "https://github.com/sstoehrm/hammer"]
                             [:licenses [:license [:name "MIT License"] [:url "https://opensource.org/licenses/MIT"]]]
                             [:scm [:url "https://github.com/sstoehrm/hammer"]
                              [:connection "scm:git:git://github.com/sstoehrm/hammer.git"]
                              [:developerConnection "scm:git:ssh://git@github.com/sstoehrm/hammer.git"]
                              [:tag (str "v" version)]]]})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file (jar-file version)})
    (println "built" (jar-file version))
    opts))

(defn deploy
  "Builds the jar and deploys it to Clojars (CLOJARS_USERNAME / CLOJARS_PASSWORD)."
  [opts]
  (jar opts)
  (let [version (version! opts)]
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path (jar-file version))
                :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
  opts)
