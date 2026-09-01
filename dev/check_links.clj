(ns check-links
  "Checks every markdown link in the docs, twice, because there are two
   audiences and a link can satisfy one and fail the other.

   REPO: someone reading the files on GitHub or in a checkout. A relative
   path has to resolve on disk from the file that contains it.

   SITE: someone reading the rendered site. Only docs/guide is built, so a
   link out of it, to docs/reference or to a source file, has nothing to
   resolve against no matter how correct it is in the repo.

   Run it with `bb docs:links`. Exits non-zero when either check fails."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def ^:private link-pattern
  ;; [text](target), not preceded by ! so images are left out.
  (re-pattern "(?<!\\!)\\[[^\\]]*\\]\\(([^)\\s]+)\\)"))

(defn- markdown-files
  "Every doc a reader might follow a link from."
  [root]
  (->> (concat (fs/glob root "docs/**.md")
               (fs/glob root "*.md")
               (fs/glob root "example/*/README.md"))
       (map str)
       (remove (fn [p] (str/includes? p "/_site/")))
       sort))

(defn- links-in
  "Every link in one file, as {:file :line :target}."
  [root path]
  (->> (str/split-lines (slurp path))
       (map-indexed (fn [i line] [(inc i) line]))
       (mapcat (fn [[n line]]
                 (->> (re-seq link-pattern line)
                      (map (fn [m]
                             {:file (str/replace path (str root "/") "")
                              :line n
                              :target (second m)})))))))

(defn- checkable?
  "Skips anything that is not a path into this repo."
  [{:keys [target]}]
  (not (or (str/starts-with? target "http://")
           (str/starts-with? target "https://")
           (str/starts-with? target "mailto:")
           (str/starts-with? target "#"))))

(defn- strip-anchor [target]
  (first (str/split target #"#")))

(defn- repo-broken
  "Links that do not resolve on disk, from the file that contains them."
  [root links]
  (->> links
       (filter checkable?)
       (remove (fn [{:keys [file target]}]
                 (let [t (strip-anchor target)
                       ;; fs/parent is nil for a file at the repo root, and
                       ;; fs/path will not take a nil segment.
                       dir (or (fs/parent file) "")]
                   (or (str/blank? t)
                       (fs/exists? (fs/path root dir t))))))))

(defn- site-broken
  "Links in guide pages that have nothing to resolve to on the built site.

   The engine builds docs/guide only, so a guide page linking to
   docs/reference, to src/, to example/ or to a root file renders as a link
   the reader can click and land on a 404."
  [links]
  (->> links
       (filter checkable?)
       (filter (fn [{:keys [file]}] (str/starts-with? file "docs/guide/")))
       (filter (fn [{:keys [target]}]
                 (let [t (strip-anchor target)]
                   (and (seq t)
                        (str/starts-with? t "..")))))))

(defn- report [label broken]
  (if (seq broken)
    (do
      (println (str "  " (count broken) " " label ":"))
      (doseq [{:keys [file line target]} (sort-by (juxt :file :line) broken)]
        (println (str "    " file ":" line "  ->  " target)))
      false)
    (do (println (str "  none " label)) true)))

(defn -main [& _]
  (let [root  (str (fs/cwd))
        files (markdown-files root)
        links (mapcat (fn [f] (links-in root f)) files)]
    (println (str "Checked " (count links) " links across " (count files) " files."))
    (println)
    (println "REPO, do they resolve on disk:")
    (let [repo-ok (report "broken in the repo" (repo-broken root links))]
      (println)
      (println "SITE, do they resolve on the built site:")
      (let [site-ok (report "unresolvable on the site" (site-broken links))]
        (println)
        (if (and repo-ok site-ok)
          (println "All links resolve for both audiences.")
          (do
            (println "A link can be right in the repo and wrong on the site.")
            (println "Only docs/guide is built, so anything reached with .. has")
            (println "no counterpart there.")
            (System/exit 1)))))))
