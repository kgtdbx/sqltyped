package sqltyped.json4s

import shapeless._
import sqltyped._
import org.json4s._
import org.json4s.native.JsonMethods._

object JSON {
  // FIXME eliminate cast
  def compact[A: toJSON.Case1](a: A): String = 
    org.json4s.native.JsonMethods.compact(render(toJSON(a).asInstanceOf[JValue]))
}

object toJSON extends Pullback1[JValue] {
  implicit def nullToJSON = at[Null](_ => JNull)
  implicit def doubleToJSON = at[Double](JDouble(_))
  implicit def bigIntToJSON = at[BigInt](JInt(_))
  implicit def numToJSON[V <% Long] = at[V](i => JInt(BigInt(i)))
  implicit def stringToJSON = at[String](s => if (s == null) JNull else JString(s))
  implicit def boolToJSON = at[Boolean](JBool(_))

  // FIXME add support for date formats
  implicit def dateToJSON[V <: java.util.Date] = 
    at[V](s => if (s == null) JNull else JString(s.toString))

  implicit def traversableToJSON[V, C[V] <: Traversable[V]](implicit st: Pullback1[V, JValue]) = 
    at[C[V]](l => JArray(l.toList.map(v => toJSON(v))))

  implicit def recordToJSON[R <: HList](implicit foldMap: MapFolder[R, List[JField], fieldToJSON.type]) = {
    at[R](r => JObject(r.foldMap(Nil: List[JField])(fieldToJSON)(_ ::: _)))
  }
}

object fieldToJSON extends Poly1 {
  implicit def value[K, V: toJSON.Case1] = at[(K, V)] {
    case (k, v) => (keyAsString(k), toJSON(v)) :: Nil
  }

  implicit def option[K, V: toJSON.Case1] = at[(K, Option[V])] { 
    case (k, Some(v)) => (keyAsString(k), toJSON(v)) :: Nil
    case (k, None) => Nil
  } 
}
