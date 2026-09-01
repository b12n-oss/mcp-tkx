(ns check-links
  "Checks every markdown link in the docs, twice, because there are two
   audiences and a link can satisfy one and fail the other.

   REPO: someone reading the files on GitHub or in a checkout. A relative
   path has to resolve on disk from the file that contains it, and an
   anchor on the end of it has to name a heading that is really there.

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

(def ^:private self-url-prefix
  (re-pattern "^https://github\\.com/b12n-oss/mcp-tkx/(?:blob|tree)/main/"))

(defn- self-link-broken
  "Links into this repo's own GitHub URL that name a path which is not there.

   Guide pages link to source and to files the site does not host, so those
   are absolute URLs rather than relative paths. That makes them resolve for a
   site reader, and it also puts them out of reach of the relative-path check,
   which is how a renamed file would rot one unnoticed."
  [root links]
  (->> links
       (filter (fn [{:keys [target]}] (re-find self-url-prefix target)))
       (remove (fn [{:keys [target]}]
                 (let [path (-> (strip-anchor target)
                                (str/replace self-url-prefix ""))]
                   (fs/exists? (fs/path root path)))))))

(def ^:private heading-pattern
  ;; A setext heading is not supported here. Every heading in this repo is
  ;; atx, and the trailing #s an atx heading may carry are not part of its text.
  (re-pattern "^(#{1,6})\\s+(.*?)\\s*#*\\s*$"))

(def ^:private fence-pattern
  (re-pattern "^\\s*(```|~~~)"))

(defn- slugify
  "One heading's text turned into the anchor GitHub would give it: markdown
   link syntax reduced to its text, lowercased, everything but letters,
   digits, underscore, hyphen and space dropped, then spaces to hyphens.

   Punctuation vanishing is what makes `## `bb check`` reachable as
   #bb-check rather than as anything containing a backtick."
  [text]
  (-> text
      (str/replace (re-pattern "\\[([^\\]]*)\\]\\([^)]*\\)") "$1")
      str/lower-case
      (str/replace (re-pattern "[^a-z0-9 _-]") "")
      str/trim
      (str/replace (re-pattern "\\s+") "-")))

(defn- heading-slugs
  "Every anchor a reader could land on in one file.

   Fence-aware on purpose. The docs are full of shell blocks whose comment
   lines start with #, and treating those as headings would mint slugs that
   let a genuinely broken anchor pass.

   A repeated heading gets GitHub's -1, -2 suffix, so the second `## Notes`
   is reachable as #notes-1."
  [path]
  ;; fs/path hands back a Path, which slurp cannot open. fs/file also
  ;; accepts the plain strings markdown-files produces.
  (loop [[line & more] (str/split-lines (slurp (fs/file path)))
         in-fence? false
         seen {}
         out #{}]
    (cond
      (nil? line) out

      (re-find fence-pattern line)
      (recur more (not in-fence?) seen out)

      in-fence?
      (recur more in-fence? seen out)

      :else
      (if-some [m (re-find heading-pattern line)]
        (let [base (slugify (nth m 2))
              n    (get seen base 0)]
          (recur more in-fence?
                 (assoc seen base (inc n))
                 (conj out (if (zero? n) base (str base "-" n)))))
        (recur more in-fence? seen out)))))

(defn- anchor-target
  "The file an anchored link points at and the fragment it wants, or nil
   when there is no fragment or it belongs to someone else's document.

   Three shapes resolve: a bare #fragment against the containing file, this
   repo's own GitHub URL against the repo root, and a relative path against
   the containing file's directory."
  [root {:keys [file target]}]
  (let [[path frag] (str/split target (re-pattern "#") 2)]
    (when (seq frag)
      (cond
        (str/blank? path)
        [(fs/path root file) frag]

        (re-find self-url-prefix target)
        [(fs/path root (str/replace path self-url-prefix "")) frag]

        (or (str/starts-with? path "http://")
            (str/starts-with? path "https://")
            (str/starts-with? path "mailto:"))
        nil

        :else
        [(fs/path root (or (fs/parent file) "") path) frag]))))

(defn- anchor-broken
  "Anchored links whose fragment names no heading in the file they point at.

   Without this the other checks stop at the file, so renaming a heading
   rots every anchor aimed at it while they all keep reporting clean. A
   target that does not exist at all is left to repo-broken rather than
   reported twice."
  [root links]
  (->> links
       (keep (fn [link]
               (when-some [[path frag] (anchor-target root link)]
                 (when (and (fs/exists? path)
                            (fs/regular-file? path)
                            (not (contains? (heading-slugs path) frag)))
                   link))))))

(defn- report [label broken]
  (if (seq broken)
    (do
      (println (str "  " (count broken) " " label ":"))
      (doseq [{:keys [file line target]} (sort-by (juxt :file :line) broken)]
        (println (str "    " file ":" line "  ->  " target)))
      false)
    (do (println (str "  none " label)) true)))

(defn -main [& _]
  (let [root    (str (fs/cwd))
        files   (markdown-files root)
        links   (mapcat (fn [f] (links-in root f)) files)
        anchored (count (filter (fn [l] (anchor-target root l)) links))]
    (println (str "Checked " (count links) " links across " (count files)
                  " files, " anchored " of them carrying an anchor."))
    (println)
    (println "REPO, do they resolve on disk:")
    ;; Every report runs before the verdict. Threading these through `and`
    ;; short-circuits, which hides the second and third failure behind the
    ;; first and makes one run tell you less than it found.
    (let [repo-ok (->> [(report "broken in the repo" (repo-broken root links))
                        (report "self-links naming a path that is gone"
                                (self-link-broken root links))
                        (report "anchors naming a heading that is gone"
                                (anchor-broken root links))]
                       (every? true?))]
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
