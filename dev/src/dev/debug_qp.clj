(ns dev.debug-qp
  "TODO -- I think this should be moved to something like [[metabase.test.util.debug-qp]]"
  (:require [clojure.data :as data]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [medley.core :as m]
            [metabase.mbql.schema :as mbql.s]
            [metabase.mbql.util :as mbql.u]
            [metabase.models.field :refer [Field]]
            [metabase.models.table :refer [Table]]
            [metabase.query-processor :as qp]
            [metabase.query-processor.reducible :as qp.reducible]
            [metabase.util :as u]
            [toucan.db :as db]))

;;;; [[add-names]]

(defn- field-and-table-name [field-id]
  (let [{field-name :name, table-id :table_id} (db/select-one [Field :name :table_id] :id field-id)]
    [(db/select-one-field :name Table :id table-id) field-name]))

(defn- add-table-id-name [table-id]
  (list 'do
        (symbol (format "#_%s" (pr-str (db/select-one-field :name Table :id table-id))))
        table-id))

(defn add-names
  "Walk a MBQL snippet `x` and add comment forms with the names of the Fields referenced to any `:field` clauses nil
  encountered. Helpful for debugging!"
  [x]
  (walk/postwalk
   (fn add-names* [form]
     (letfn [(add-name-to-field-id [id]
               (when id
                 (let [[field-name table-name] (field-and-table-name id)]
                   (symbol (format "#_\"%s.%s\"" field-name table-name)))))
             (field-id->name-form [field-id]
               (list 'do (add-name-to-field-id field-id) field-id))]
       (mbql.u/replace form
         [:field (id :guard integer?) opts]
         [:field id (add-name-to-field-id id) (cond-> opts
                                                (integer? (:source-field opts))
                                                (update :source-field field-id->name-form))]

         (m :guard (every-pred map? (comp integer? :source-table)))
         (add-names* (update m :source-table add-table-id-name))

         (m :guard (every-pred map? (comp integer? :metabase.query-processor.util.add-alias-info/source-table)))
         (add-names* (update m :metabase.query-processor.util.add-alias-info/source-table add-table-id-name))

         (m :guard (every-pred map? (comp integer? :fk-field-id)))
         (-> m
             (update :fk-field-id field-id->name-form)
             add-names*)

         ;; don't recursively replace the `do` lists above, other we'll get vectors.
         (_ :guard (every-pred list? #(= (first %) 'do)))
         &match)))
   x))


;;;; [[process-query-debug]]

;; see docstring for [[process-query-debug]] for descriptions of what these do.

(def ^:private ^:dynamic *print-full?*     true)
(def ^:private ^:dynamic *print-metadata?* false)
(def ^:private ^:dynamic *print-names?*    true)
(def ^:private ^:dynamic *validate-query?* false)


(defn- remove-metadata
  "Replace field metadata in `x` with `...`."
  [x]
  (walk/prewalk
   (fn [form]
     (if (map? form)
       (reduce
        (fn [m k]
          (m/update-existing m k (constantly '...)))
        form
        [:cols :results_metadata :source-metadata])
       form))
   x))

(defn- format-output [x]
  (cond-> x
    (not *print-metadata?*) remove-metadata
    *print-names?*          add-names))

(defn- print-diff [before after]
  (assert (not= before after))
  (let [before                         (format-output before)
        after                          (format-output after)
        [only-in-before only-in-after] (data/diff before after)]
    (when *print-full?*
      (println (u/pprint-to-str 'cyan (format-output after))))
    (when (seq only-in-before)
      (println (u/colorize 'red (str "-\n" (u/pprint-to-str only-in-before)))))
    (when (seq only-in-after)
      (println (u/colorize 'green (str "+\n" (u/pprint-to-str only-in-after)))))))

(defn- debug-query-changes [middleware-var middleware]
  (fn [next-middleware]
    (fn [query-before rff context]
      (try
        ((middleware
          (fn [query-after rff context]
            (when-not (= query-before query-after)
              (println (format "[pre] %s transformed query:" middleware-var))
              (print-diff query-before query-after))
            (when *validate-query?*
              (try
                (mbql.s/validate-query query-after)
                (catch Throwable e
                  (when (::our-error? (ex-data e))
                    (throw e))
                  (throw (ex-info (format "%s middleware produced invalid query" middleware-var)
                                  {::our-error? true
                                   :middleware  middleware-var
                                   :before      query-before
                                   :query       query-after}
                                  e)))))
            (next-middleware query-after rff context)))
         query-before rff context)
        (catch Throwable e
          (when (::our-error? (ex-data e))
            (throw e))
          (println (format "Error pre-processing query in %s:\n%s"
                           middleware-var
                           (u/pprint-to-str 'red (Throwable->map e))))
          (throw (ex-info "Error pre-processing query"
                          {::our-error? true
                           :middleware  middleware-var
                           :query       query-before}
                          e)))))))

(defn- debug-rffs [middleware-var middleware before-rff-xform after-rff-xform]
  (fn [next-middleware]
    (fn [query rff-after context]
      ((middleware
        (fn [query rff-before context]
          (next-middleware query (before-rff-xform rff-before) context)))
       query (after-rff-xform rff-after) context))))

(defn- debug-metadata-changes [middleware-var middleware]
  (let [before (atom nil)]
    (debug-rffs
     middleware-var
     middleware
     (fn before-rff-xform [rff]
       (fn [metadata-before]
         (reset! before metadata-before)
         (try
           (rff metadata-before)
           (catch Throwable e
             (when (::our-error? (ex-data e))
               (throw e))
             (println (format "Error post-processing result metadata in %s:\n%s"
                              middleware-var
                              (u/pprint-to-str 'red (Throwable->map e))))
             (throw (ex-info "Error post-processing result metadata"
                             {::our-error? true
                              :middleware  middleware-var
                              :metadata    metadata-before}
                             e))))))
     (fn after-rff-xform [rff]
       (fn [metadata-after]
         (when-not (= @before metadata-after)
           (println (format "[post] %s transformed metadata:" middleware-var))
           (print-diff @before metadata-after))
         (rff metadata-after))))))

(defn- debug-rfs [middleware-var middleware before-xform after-xform]
  (debug-rffs
   middleware-var
   middleware
   (fn before-rff-xform [rff]
     (fn [metadata]
       (let [rf (rff metadata)]
         (before-xform rf))))
   (fn after-rff-xform [rff]
     (fn [metadata]
       (let [rf (rff metadata)]
         (after-xform rf))))))

(defn- debug-result-changes [middleware-var middleware]
  (let [before (atom nil)]
    (debug-rfs
     middleware-var
     middleware
     (fn before-xform [rf]
       (fn
         ([] (rf))
         ([result]
          (reset! before result)
          (try
            (rf result)
            (catch Throwable e
              (when (::our-error? (ex-data e))
                (throw e))
              (println (format "Error post-processing result in %s:\n%s"
                               middleware-var
                               (u/pprint-to-str 'red (Throwable->map e))))
              (throw (ex-info "Error post-processing result"
                              {::our-error? true
                               :middleware  middleware-var
                               :result      result}
                              e)))))
         ([result row] (rf result row))))
     (fn after-xform [rf]
       (fn
         ([] (rf))
         ([result]
          (when-not (= @before result)
            (println (format "[post] %s transformed result:" middleware-var))
            (print-diff @before result))
          (rf result))
         ([result row] (rf result row)))))))

(defn- debug-row-changes [middleware-var middleware]
  (let [before (atom nil)]
    (debug-rfs
     middleware-var
     middleware
     (fn before-xform [rf]
       (fn
         ([] (rf))
         ([result]
          (rf result))
         ([result row]
          (reset! before row)
          (try
            (rf result row)
            (catch Throwable e
              (when (::our-error? (ex-data e))
                (throw e))
              (println (format "Error reducing row in %s:\n%s"
                               middleware-var
                               (u/pprint-to-str 'red (Throwable->map e))))
              (throw (ex-info "Error reducing row"
                              {::our-error? true
                               :middleware  middleware-var
                               :result      result
                               :row         row}
                              e)))))))
     (fn after-xform [rf]
       (fn
         ([] (rf))
         ([result]
          (rf result))
         ([result row]
          (when-not (= @before row)
            (println (format "[post] %s transformed row" middleware-var))
            (print-diff @before row))
          (rf result row)))))))

(defn process-query-debug
  "Process a query using a special QP that wraps all of the normal QP middleware and prints any transformations done
  during pre or post-processing.

  Options:

  * `:print-full?` -- whether to print the entire query/result/etc. after each transformation

  * `:print-metadata?` -- whether to print metadata columns such as `:cols`or `:source-metadata`
    in the query/results

  * `:print-names?` -- whether to print comments with the names of fields/tables as part of `:field` forms and
    for `:source-table`

  * `:validate-query?` -- whether to validate the query after each preprocessing step, so you can figure out who's
    breaking it. (TODO -- `mbql-to-native` middleware currently leaves the old mbql `:query` in place,
    which cases query to fail at that point -- manually comment that behavior out if needed"
  [query & {:keys [print-full? print-metadata? print-names? validate-query? context]
            :or   {print-full? true, print-metadata? false, print-names? true, validate-query? false}}]
  (binding [*print-full?*               print-full?
            *print-metadata?*           print-metadata?
            *print-names?*              print-names?
            *validate-query?*           validate-query?
            pprint/*print-right-margin* 80]
    (let [middleware (for [middleware-var qp/default-middleware
                           :when          middleware-var]
                       (->> middleware-var
                            (debug-query-changes middleware-var)
                            (debug-metadata-changes middleware-var)
                            (debug-result-changes middleware-var)
                            (debug-row-changes middleware-var)))
          qp         (qp.reducible/sync-qp (#'qp/base-qp middleware))]
      (if context
        (qp query context)
        (qp query)))))


;;;; [[to-mbql-shorthand]]

(defn- strip-$ [coll]
  (into []
        (map (fn [x] (if (= x ::$) ::no-$ x)))
        coll))

(defn- can-symbolize? [x]
  (mbql.u/match-one x
    (_ :guard string?)
    (not (re-find #"\s+" x))

    [:field (id :guard integer?) nil]
    (every? can-symbolize? (field-and-table-name id))

    [:field (field-name :guard string?) (opts :guard #(= (set (keys %)) #{:base-type}))]
    (can-symbolize? field-name)

    [:field _ (opts :guard :join-alias)]
    (and (can-symbolize? (:join-alias opts))
         (can-symbolize? (mbql.u/update-field-options &match dissoc :join-alias)))

    [:field _ (opts :guard :temporal-unit)]
    (and (can-symbolize? (name (:temporal-unit opts)))
         (can-symbolize? (mbql.u/update-field-options &match dissoc :temporal-unit)))

    [:field _ (opts :guard :source-field)]
    (let [source-field-id (:source-field opts)]
      (and (can-symbolize? [:field source-field-id nil])
           (can-symbolize? (mbql.u/update-field-options &match dissoc :source-field))))

    _
    false))

(defn- expand [form table]
  (try
    (mbql.u/replace form
      ([:field (id :guard integer?) nil] :guard can-symbolize?)
      (let [[table-name field-name] (field-and-table-name id)
            field-name              (some-> field-name str/lower-case)
            table-name              (some-> table-name str/lower-case)]
        (if (= table-name table)
          [::$ field-name]
          [::$ table-name field-name]))

      ([:field (field-name :guard string?) (opts :guard #(= (set (keys %)) #{:base-type}))] :guard can-symbolize?)
      [::* field-name (name (:base-type opts))]

      ([:field _ (opts :guard :temporal-unit)] :guard can-symbolize?)
      (let [without-unit (mbql.u/update-field-options &match dissoc :temporal-unit)
            expansion    (expand without-unit table)]
        [::! (name (:temporal-unit opts)) (strip-$ expansion)])

      ([:field _ (opts :guard :source-field)] :guard can-symbolize?)
      (let [without-source-field   (mbql.u/update-field-options &match dissoc :source-field)
            expansion              (expand without-source-field table)
            source-as-field-clause [:field (:source-field opts) nil]
            source-expansion       (expand source-as-field-clause table)]
        [::-> source-expansion expansion])

      ([:field _ (opts :guard :join-alias)] :guard can-symbolize?)
      (let [without-join-alias (mbql.u/update-field-options &match dissoc :join-alias)
            expansion          (expand without-join-alias table)]
        [::& (:join-alias opts) expansion])

      [:field (id :guard integer?) opts]
      (let [without-opts [:field id nil]
            expansion    (expand without-opts table)]
        (if (= expansion without-opts)
          &match
          [:field [::% (strip-$ expansion)] opts]))

      (m :guard (every-pred map? (comp integer? :source-table)))
      (-> (update m :source-table (fn [table-id]
                                    [::$$ (some-> (db/select-one-field :name Table :id table-id) str/lower-case)]))
          (expand table))

      (m :guard (every-pred map? (comp integer? :fk-field-id)))
      (-> (update m :fk-field-id (fn [fk-field-id]
                                   (let [[table-name field-name] (field-and-table-name fk-field-id)
                                         field-name              (some-> field-name str/lower-case)
                                         table-name              (some-> table-name str/lower-case)]
                                     (if (= table-name table)
                                       [::% field-name]
                                       [::% table-name field-name]))))
          (expand table)))
    (catch Throwable e
      (throw (ex-info (format "Error expanding %s: %s" (pr-str form) (ex-message e))
                      {:form form, :table table}
                      e)))))

(defn- no-$ [x]
  (mbql.u/replace x [::$ & args] (into [::no-$] args)))

(def ^:private mbql-clause->sort-order
  (into {}
        (map-indexed (fn [i k]
                       [k i]))
        [;; top-level keys
         :database
         :type
         :query
         :native
         ;; inner-query keys
         :source-table
         :source-query
         :source-metadata
         :joins
         :expressions
         :breakout
         :aggregation
         :fields
         :filter
         :order-by
         :page
         :limit
         ;; join keys
         :alias
         :condition
         :strategy]))

(defn- sorted-mbql-query-map []
  (sorted-map-by (fn [x y]
                   (let [x-order (mbql-clause->sort-order x)
                         y-order (mbql-clause->sort-order y)]
                     (cond
                       (and x-order y-order) (compare x-order y-order)
                       x-order               -1
                       y-order               1
                       :else                 (compare (pr-str x) (pr-str y)))))))

(defn- symbolize [form]
  (mbql.u/replace form
    [::-> x y]
    (symbol (format "%s->%s" (symbolize x) (str/replace (symbolize y) #"^\$" "")))

    [::no-$ & args]
    (str/join \. args)

    [(qualifier :guard #{::$ ::& ::! ::%}) & args]
    (symbol (str (name qualifier) (str/join \. (symbolize (no-$ args)))))

    [::* field-name base-type]
    (symbol (format "*%s/%s" field-name base-type))

    [::$$ table-name]
    (symbol (format "$$%s" table-name))))

(defn- query-table-name [{:keys [source-table source-query]}]
  (cond
    source-table
    (str/lower-case (db/select-one-field :name Table :id source-table))

    source-query
    (recur source-query)))

(defn to-mbql-shorthand
  ([query]
   (to-mbql-shorthand query (query-table-name (:query query))))

  ([query table-name]
   (let [symbolized (-> query (expand table-name) symbolize)
         table-symb (some-> table-name symbol)]
     (if (:query symbolized)
       (list 'mt/mbql-query table-symb (-> (:query symbolized)
                                           (dissoc :source-table)))
       (list 'mt/$ids table-symb symbolized)))))

(defn expand-symbolize [x]
  (-> x (expand "orders") symbolize))

;; tests are in [[dev.debug-qp-test]] (in `./dev/test/dev` dir)
