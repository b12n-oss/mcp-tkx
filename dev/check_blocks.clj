(ns check-blocks
  "Reads every fenced Clojure block in the docs and reports the ones that do
   not parse.

   A reader who copies a block expects it to compile. One in the 2025-06-18
   migration was a close paren short for long enough that two review passes
   read past it, because an unbalanced block looks fine until you try it.

   Blocks are read with a strict top-level `read` loop rather than
   `read-string`, which consumes one form and ignores the rest, so a file whose
   first form is balanced passes while later ones are broken.

   Some blocks are fragments on purpose: a snippet of a larger form, or prose
   with a `;; =>` result. Those are skipped by a marker comment on the fence,
   ```clojure fragment, so skipping is a decision someone wrote down rather
   than a silent pass.

   Run it with `bb docs:blocks`."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(defn- blocks
  "Every fenced clojure block in one file, as {:file :line :fragment? :code}."
  [path]
  (let [lines (str/split-lines (slurp path))]
    (loop [[line & more] lines, n 1, open nil, acc [], out []]
      (cond
        (nil? line)
        out

        (some? open)
        (if (str/starts-with? (str/trim line) "```")
          (recur more (inc n) nil []
                 (conj out {:file path :line (:line open)
                            :fragment? (:fragment? open)
                            :code (str/join "\n" acc)}))
          (recur more (inc n) open (conj acc line) out))

        (str/starts-with? (str/trim line) "```clojure")
        (recur more (inc n)
               {:line n :fragment? (str/includes? line "fragment")}
               [] out)

        :else
        (recur more (inc n) nil [] out)))))

(defn- parse-error
  "nil when the code reads to EOF, else the reader's message."
  [code]
  (try
    (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
      (loop []
        (let [form (read {:read-cond :allow :eof ::eof} r)]
          (if (= form ::eof) nil (recur)))))
    (catch Exception e (.getMessage e))))

(defn -main [& _]
  (let [root  (str (fs/cwd))
        files (->> (concat (fs/glob root "docs/**.md") (fs/glob root "*.md")
                           (fs/glob root "example/*/README.md"))
                   (map str)
                   (remove (fn [p] (str/includes? p "/_site/")))
                   sort)
        all   (mapcat blocks files)
        checked (remove :fragment? all)
        broken  (keep (fn [b]
                        (when-some [err (parse-error (:code b))]
                          (assoc b :error err)))
                      checked)]
    (println (str "Read " (count checked) " Clojure blocks across " (count files)
                  " files, skipping " (- (count all) (count checked))
                  " marked as fragments."))
    (println)
    (if (seq broken)
      (do
        (println (str "  " (count broken) " that do not parse:"))
        (doseq [{:keys [file line error]} broken]
          (println (str "    " (str/replace file (str root "/") "") ":" line
                        "  " error)))
        (System/exit 1))
      (println "  every block parses"))))
