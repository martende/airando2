package utils

import scala.collection.{immutable,mutable}
 
object Utils2 {
  implicit class RichTraversable[A](traversable: Traversable[A]) {
    // find N minimal values
    def mins[B >: A](n: Int)(implicit cmp: Ordering[B]): immutable.IndexedSeq[A] = {
      val bufa = new mutable.ArrayBuffer[A]
      bufa sizeHint n
      var maxi = -1 // index of the biggest element in `bufa`
      for (a <- traversable) {
        if (bufa.length < n) {
          bufa += a
          if (bufa.length == n)
            maxi = bufa.indexOf(bufa.max(cmp))
        } else if (cmp.lt(a, bufa(maxi))) { // replace the biggest element with new one, find new biggest
          bufa(maxi) = a
          maxi = bufa.indexOf(bufa.max(cmp))
        }
      }
      bufa.toIndexedSeq
    }
     
    def minsBy[B](n: Int, f: A=>B)(implicit cmp: Ordering[B]): immutable.IndexedSeq[A] = {
      val bufa = new mutable.ArrayBuffer[A]
      bufa sizeHint n
      var bufb: mutable.ArrayBuffer[B] = null
      var maxi = -1 // index of the biggest element in `bufb`
      for (a <- traversable) {
        if (bufa.length < n) {
          bufa += a
          if (bufa.length == n) {
            bufb = bufa map f
            maxi = bufb.indexOf(bufb.max(cmp))
          }
        } else {
          val b = f(a)
          if (cmp.lt(b, bufb(maxi))) { // replace the biggest element with new one, find new biggest
            bufa(maxi) = a
            bufb(maxi) = b
            maxi = bufb.indexOf(bufb.max(cmp))
          }
        }
      }
      bufa.toIndexedSeq
    }
  }
}