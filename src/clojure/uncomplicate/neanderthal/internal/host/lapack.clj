;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.neanderthal.internal.host.lapack
  (:require [uncomplicate.neanderthal.block :refer [buffer offset stride]]
            [uncomplicate.neanderthal.internal.api
             :refer [factory index-factory engine stripe-navigator create-vector create-ge create-sy
                     fits-navigation? nrm1 nrmi trf tri trs con sv copy]]
            [uncomplicate.commons.core :refer [with-release let-release]])
  (:import [uncomplicate.neanderthal.internal.host CBLAS]
           [uncomplicate.neanderthal.internal.api GEMatrix]))

(defmacro with-lapack-check [expr]
  ` (let [err# ~expr]
      (if (zero? err#)
        err#
        (throw (ex-info "LAPACK error." {:error-code err#})))))

;; =========================== Auxiliary LAPACK Routines =========================

;; ----------------- Common vector macros and functions -----------------------

(defmacro vctr-laset [method alpha x]
  `(with-lapack-check
     (~method CBLAS/ORDER_ROW_MAJOR (int \g) (.dim ~x) 1 ~alpha ~alpha (.buffer ~x) (.offset ~x) (.stride ~x))))

(defmacro vctr-lasrt [method x increasing]
  `(if (= 1 (.stride ~x))
     (with-lapack-check
       (~method (int (if ~increasing \I \D)) (.dim ~x) (.buffer ~x) (.offset ~x)))
     (throw (ex-info "You cannot sort a vector with stride different than 1." {:stride (.stride ~x)}))))

;; ----------------- Common GE matrix macros and functions -----------------------

(defmacro ge-lan [method norm a]
  `(~method (.order ~a) ~norm (.mrows ~a) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)))

(defmacro ge-laset [method alpha beta a]
  `(with-lapack-check
     (~method (.order ~a) (int \g) (.mrows ~a) (.ncols ~a) ~alpha ~beta (.buffer ~a) (.offset ~a) (.stride ~a))))

(defmacro ge-lasrt [method a increasing]
  `(let [n# (.fd ~a)
         ld-a# (.stride ~a)
         sd-a# (.sd ~a)
         offset-a# (.offset ~a)
         buff-a# (.buffer ~a)
         incr# (int (if ~increasing \I \D))]
     (dotimes [j# n#]
       (with-lapack-check (~method incr# sd-a# buff-a# (+ offset-a# (* j# ld-a#)))))))

;; ----------------- Common UPLO matrix macros and functions -----------------------

(defmacro uplo-lasrt [method a increasing]
  `(let [stripe-nav# (stripe-navigator ~a)
         n# (.fd ~a)
         ld-a# (.stride ~a)
         sd-a# (.sd ~a)
         offset-a# (.offset ~a)
         buff-a# (.buffer ~a)
         incr# (int (if ~increasing \I \D))]
     (dotimes [j# n#]
       (let [start# (.start stripe-nav# n# j#)
             n-j# (- (.end stripe-nav# n# j#) start#)]
         (with-lapack-check (~method incr# n-j# buff-a# (+ offset-a# (* j# ld-a#) start#)))))))

;; ----------------- Common TR matrix macros and functions -----------------------

;; There seems to be a bug in MKL's LAPACK_?lantr. If the order is column major,
;; it returns 0.0 as a result. To fix this, I had to do the uplo# trick.
;; NOTE: This means that nrm1 and nrmi work regarding to max row instead of max col
;; for column-oriented matrices...
(defmacro tr-lan [method norm a]
  `(let [uplo# (if (= CBLAS/ORDER_COLUMN_MAJOR (.order ~a))
                 (if (= CBLAS/UPLO_LOWER (.uplo ~a)) \U \L)
                 (if (= CBLAS/UPLO_LOWER (.uplo ~a)) \L \U))]
     (~method CBLAS/ORDER_ROW_MAJOR ~norm
      (int uplo#) (int (if (= CBLAS/DIAG_UNIT (.diag ~a)) \U \N))
      (.mrows ~a) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a))))

(defmacro tr-lacpy [lacpy copy a b]
  `(let [stripe-nav# (stripe-navigator ~a)]
     (if (= (.order ~a) (.order ~b))
       (with-lapack-check
         (let [stripe-nav# (stripe-navigator ~a)
               unit# (= CBLAS/DIAG_UNIT (.diag ~a))
               diag-pad# (long (if unit# 1 0))
               diag-ofst# (.offsetPad stripe-nav# (.stride ~a))]
           (~lacpy (.order ~a) (int (if (= CBLAS/UPLO_LOWER (.uplo ~a)) \L \U))
            (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
            (.buffer ~a) (+ (.offset ~a) diag-ofst#) (.stride ~a)
            (.buffer ~b) (+ (.offset ~b) diag-ofst#) (.stride ~b))))
       (let [n# (.fd ~a)
             ld-a# (.stride ~a)
             offset-a# (.offset ~a)
             buff-a# (.buffer ~a)
             ld-b# (.stride ~b)
             offset-b# (.offset ~b)
             buff-b# (.buffer ~b)]
         (dotimes [j# n#]
           (let [start# (.start stripe-nav# n# j#)
                 n-j# (- (.end stripe-nav# n# j#) start#)]
             (~copy n-j# buff-a# (+ offset-a# (* ld-a# j#) start#) 1
              buff-b# (+ offset-b# j# (* ld-b# start#)) n#)))))))

(defmacro tr-lascl [method alpha a]
  `(with-lapack-check
     (let [diag-pad# (long (if (= CBLAS/DIAG_UNIT (.diag ~a)) 1 0))]
       (~method (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
        0 0 1.0 ~alpha (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
        (.buffer ~a) (+ (.offset ~a) diag-pad#) (.stride ~a)))))

(defmacro tr-laset [method alpha beta a]
  `(with-lapack-check
     (let [stripe-nav# (stripe-navigator ~a)
           unit# (= CBLAS/DIAG_UNIT (.diag ~a))
           diag-pad# (long (if unit# 1 0))
           diag-ofst# (.offsetPad stripe-nav# (.stride ~a))]
       (~method (.order ~a) (int (if (= CBLAS/UPLO_LOWER (.uplo ~a)) \L \U))
        (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
        ~alpha ~beta (.buffer ~a) (+ (.offset ~a) diag-ofst#) (.stride ~a)))))

;; ----------- Symmetric Matrix -----------------------------------------------------

(defmacro sy-lan [method norm a]
  `(~method (.order ~a) ~norm (int (if (= CBLAS/UPLO_LOWER (.uplo ~a)) \L \U))
    (.mrows ~a) (.buffer ~a) (.offset ~a) (.stride ~a)))

(defmacro sy-lacpy [lacpy copy a b]
  `(if (= (.order ~a) (.order ~b))
     (with-lapack-check
       (~lacpy (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L)) (.mrows ~a) (.ncols ~a)
        (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))
     (let [stripe-nav# (stripe-navigator ~a)
           n# (.fd ~a)
           ld-a# (.stride ~a)
           offset-a# (.offset ~a)
           buff-a# (.buffer ~a)
           ld-b# (.stride ~b)
           offset-b# (.offset ~b)
           buff-b# (.buffer ~b)]
       (dotimes [j# n#]
         (let [start# (.start stripe-nav# n# j#)
               n-j# (- (.end stripe-nav# n# j#) start#)]
           (~copy n-j# buff-a# (+ offset-a# (* ld-a# j#) start#) 1
            buff-b# (+ offset-b# j# (* ld-b# start#)) n#))))))

(defmacro sy-lascl [method alpha a]
  `(with-lapack-check
     (~method (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
      0 0 1.0 ~alpha (.mrows ~a) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a))))

(defmacro sy-laset [method alpha beta a]
  `(with-lapack-check
     (~method (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
      (.mrows ~a) (.ncols ~a) ~alpha ~beta (.buffer ~a) (.offset ~a) (.stride ~a))))

;; =========== Drivers and Computational LAPACK Routines ===========================

;; ------------- Singular Value Decomposition LAPACK -------------------------------

(defmacro with-sv-check [ipiv expr]
  `(if (= 1 (stride ~ipiv))
     (let [info# ~expr]
       (cond
         (= 0 info#) ~ipiv
         (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                     {:arg-index (- info#)}))
         :else (throw (ex-info "The factor U is singular, the solution could not be computed."
                               {:info info#}))))
     (throw (ex-info "You cannot use ipiv with stride different than 1." {:stride (stride ~ipiv)}))))

(defmacro ge-trf [method a ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~a) (.mrows ~a) (.ncols ~a)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~ipiv) (.offset ~ipiv))))

(defmacro sy-trf [method a ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L)) (.sd ~a)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~ipiv) (.offset ~ipiv))))

(defmacro ge-tri [method a ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~a) (.sd ~a)
      (.buffer ~a) (.offset ~a) (.ld ~a) (.buffer ~ipiv) (.offset ~ipiv))))

(defmacro tr-tri [method a]
  `(~method (.order ~a)
    (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
    (int (if (= CBLAS/DIAG_UNIT (.diag ~a)) \U \N))
    (.sd ~a) (.buffer ~a) (.offset ~a) (.ld ~a)))

(defmacro sy-tri [method a ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~a) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L)) (.sd ~a)
      (.buffer ~a) (.offset ~a) (.ld ~a) (.buffer ~ipiv) (.offset ~ipiv))))

(defmacro ge-trs [method a b ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~b) (int (if (= (.order ~b) (.order ~a)) \N \T))
      (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a)
      (.buffer ~ipiv) (.offset ~ipiv) (.buffer ~b) (.offset ~b) (.stride ~b))))

(defmacro tr-trs [method a b]
  `(~method (.order ~b)
    (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L)) (int (if (= (.order ~b) (.order ~a)) \N \T))
    (int (if (= CBLAS/DIAG_UNIT (.diag ~a)) \U \N)) (.mrows ~b)
    (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))

(defmacro sy-trs [method a b ipiv]
  `(with-sv-check ~ipiv
     (~method (.order ~b) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
      (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a)
      (.buffer ~ipiv) (.offset ~ipiv) (.buffer ~b) (.offset ~b) (.stride ~b))))

(defmacro ge-sv
  ([method a b pure]
   `(if ~pure
      (with-release [a# (create-ge (factory ~a) (.mrows ~a) (.ncols ~a) (.order ~b) false)]
        (copy (engine ~a) ~a a#)
        (sv (engine ~a) a# ~b false))
      (if (fits-navigation? ~a ~b)
        (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) nil)]
          (with-sv-check ipiv#
            (~method (.order ~b) (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a)
             (buffer ipiv#) (offset ipiv#) (.buffer ~b) (.offset ~b) (.stride ~b))))
        (throw (ex-info "Orientation of a and b do not fit." {:a (str ~a) :b (str ~b)}))))))

(defmacro tr-sv [method a b]
  `(~method (.order ~b) CBLAS/SIDE_LEFT
    (if (= (.order ~a) (.order ~b))
      (.uplo ~a)
      (if (= CBLAS/UPLO_LOWER (.uplo ~a)) CBLAS/UPLO_UPPER CBLAS/UPLO_LOWER))
    (if (= (.order ~a) (.order ~b)) CBLAS/TRANSPOSE_NO_TRANS CBLAS/TRANSPOSE_TRANS)
    (.diag ~a) (.mrows ~b) (.ncols ~b) 1.0
    (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))

(defmacro sy-sv
  ([method a b pure]
   `(if ~pure
      (with-release [a# (create-sy (factory ~a) (.ncols ~a) (.order ~b) (.uplo ~a) false)]
        (copy (engine ~a) ~a a#)
        (sv (engine ~a) a# ~b false))
      (if (fits-navigation? ~a ~b)
        (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) nil)]
          (with-sv-check ipiv#
            (~method (.order ~b) (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
             (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a)
             (buffer ipiv#) (offset ipiv#) (.buffer ~b) (.offset ~b) (.stride ~b))))
        (throw (ex-info "Orientation of a and b do not fit." {:a (str ~a) :b (str ~b)}))))))

;; ------------------ Condition Number ----------------------------------------------

(defmacro ge-con [da method lu nrm nrm1?]
  `(with-release [res# (.createDataSource ~da 1)]
     (let [info# (~method (.order ~lu) (int (if ~nrm1? \O \I)) (min (.mrows ~lu) (.ncols ~lu))
                  (.buffer ~lu) (.offset ~lu) (.stride ~lu) ~nrm res#)]
       (if (= 0 info#)
         (.get ~da res# 0)
         (throw (ex-info "There has been an illegal argument in the native function call."
                         {:arg-index (- info#)}))))))

(defmacro tr-con [da method a nrm1?]
  `(with-release [res# (.createDataSource ~da 1)
                  info# (~method (.order ~a) (int (if ~nrm1? \O \I))
                         (int (if (= CBLAS/UPLO_UPPER (.uplo ~a)) \U \L))
                         (int (if (= CBLAS/DIAG_UNIT (.diag ~a)) \U \N))
                         (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a) res#)]
     (if (= 0 info#)
       (.get ~da res# 0)
       (throw (ex-info "There has been an illegal argument in the native function call."
                       {:arg-index (- info#)})))))

(defmacro sy-con [da method ldl ipiv nrm]
  `(with-release [res# (.createDataSource ~da 1)]
     (let [info# (~method (.order ~ldl) (int (if (= CBLAS/UPLO_UPPER (.uplo ~ldl)) \U \L)) (.ncols ~ldl)
                  (.buffer ~ldl) (.offset ~ldl) (.stride ~ldl) (.buffer ~ipiv) (.offset ~ipiv) ~nrm res#)]
       (if (= 0 info#)
         (.get ~da res# 0)
         (throw (ex-info "There has been an illegal argument in the native function call."
                         {:arg-index (- info#)}))))))

;; ------------- Orthogonal Factorization (L, Q, R) LAPACK -------------------------------

(defmacro with-lqr-check [tau res expr]
  `(if (= 1 (.stride ~tau))
     (let [info# ~expr]
       (if (= 0 info#)
         ~res
         (throw (ex-info "There has been an illegal argument in the native function call."
                         {:arg-index (- info#)}))))
     (throw (ex-info "You cannot use tau with stride different than 1." {:stride (.stride ~tau)}))))

(defmacro ge-lqrf [method a tau]
  `(with-lqr-check ~tau ~tau
     (~method (.order ~a) (.mrows ~a) (.ncols ~a)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~tau) (.offset ~tau))))

(defmacro or-glqr [method a tau]
  `(with-lqr-check ~tau ~a
     (~method (.order ~a) (.mrows ~a) (.ncols ~a) (.dim ~tau)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~tau) (.offset ~tau))))

(defmacro or-mlqr [method a tau c left]
  `(with-lqr-check ~tau ~c
     (~method (.order ~c) (int (if ~left \L \R)) (int (if (= (.order ~a) (.order ~c)) \N \T))
      (.mrows ~c) (.ncols ~c) (.dim ~tau) (.buffer ~a) (.offset ~a) (.stride ~a)
      (.buffer ~tau) (.offset ~tau) (.buffer ~c) (.offset ~c) (.stride ~c))))

;; ------------- Linear Least Squares Routines LAPACK -------------------------------

(defmacro ge-ls [method a b]
  `(let [info# (~method (.order ~a) (int (if (= (.order ~a) (.order ~b)) \N \T))
                (.mrows ~a) (.ncols ~a) (.ncols ~b)
                (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b))]
     (cond
       (= 0 info#) ~b
       (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                   {:arg-index (- info#)}))
       :else (throw (ex-info "The i-th diagonal element of a is zero, so the matrix does not have full rank."
                             {:arg-index info#})))))

;; ------------- Non-Symmetric Eigenvalue Problem Routines LAPACK -------------------------------

(defmacro ge-ev [method a w vl vr]
  `(if (= CBLAS/ORDER_COLUMN_MAJOR (.order ~w))
     (let [wr# (.col ~w 0)
           wi# (.col ~w 1)
           info# (~method (.order ~a) (int (if (< 0 (.mrows ~vl)) \V \N)) (int (if (< 0 (.mrows ~vr)) \V \N))
                  (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)
                  (buffer wr#) (offset wr#) (buffer wi#) (offset wi#)
                  (.buffer ~vl) (.offset ~vl) (.stride ~vl) (.buffer ~vr) (.offset ~vr) (.stride ~vr))]
       (cond
         (= 0 info#) ~w
         (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                     {:arg-index (- info#)}))
         :else (throw (ex-info "The QR algorithm failed to compute all the eigenvalues."
                               {:first-converged (inc info#)}))))
     (throw (ex-info "You cannot use w that is not column-oriented and has less than 2 columns."
                     {:order (.order ~w) :ncols (.ncols ~w)}))))

;; ------------- Singular Value Decomposition Routines LAPACK -------------------------------

(defmacro with-svd-check [s expr]
  `(let [info# ~expr]
     (cond
       (= 0 info#) ~s
       (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                   {:arg-index (- info#)}))
       :else (throw (ex-info "The reduction to bidiagonal form did not converge"
                             {:non-converged-superdiagonals info#})))))

(defmacro ge-svd
  ([method a s u vt superb]
   `(let [m# (.mrows ~a)
          n# (.ncols ~a)]
      (with-svd-check ~s
        (~method (.order ~a)
         (int (cond (= m# (.mrows ~u) (.ncols ~u)) \A
                    (and (= m# (.mrows ~u)) (= (min m# n#) (.ncols ~u))) \S
                    (nil? ~u) \O
                    :else \N))
         (int (cond (= n# (.mrows ~vt) (.ncols ~vt)) \A
                    (and (= (min m# n#) (.mrows ~vt)) (= n# (.ncols ~vt))) \S
                    (and ~u (nil? ~vt)) \O
                    :else \N))
         m# n# (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~s) (.offset ~s)
         (.buffer ~u) (.offset ~u) (.stride ~u) (.buffer ~vt) (.offset ~vt) (.stride ~vt)
         (.buffer ~superb) (.offset ~superb)))))
  ([method a s zero-uvt superb]
   `(with-svd-check ~s
      (~method (.order ~a) (int \N) (int \N) (.mrows ~a) (.ncols ~a)
       (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~s) (.offset ~s)
       (.buffer ~zero-uvt) (.offset ~zero-uvt) (.stride ~zero-uvt)
       (.buffer ~zero-uvt) (.offset ~zero-uvt) (.stride ~zero-uvt)
       (.buffer ~superb) (.offset ~superb)))))
