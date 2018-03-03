(defn zip
  "Takes multiple collections a b c ... and returns a single collection of
   tuples ([a0 b0 c0 ...] [a1 b1 c1 ...] [a2 b2 c2 ...] ...) with the lenght
   of shortest collection"
  [& as]
  (apply map vector as))