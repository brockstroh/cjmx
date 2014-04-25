package cjmx.util.jmx

import javax.management.{MBeanServerConnection,ObjectName,QueryExp,Query=>Q,StringValueExp,ValueExp}
import scala.collection.JavaConverters._
import scalaz.\/

/** Typed API for interacting with MBeans. */
object Beans extends ToRichMBeanServerConnection {

  private[cjmx] val unnamed = SubqueryName("#0") // used when user does not name the subquery

  // newtyping these to keep them straight

  /** The name of a subquery, which may return multiple `Result` values. */
  case class SubqueryName(get: String)

  /**
   * A subquery, which may return multiple `Result` values.
   * Example `mbeans 'java.nio:*' where Name='mapped' select *` would be represented as:
   * `Subquery(unnamed, new ObjectName("java.nio:*"), Some(Name='mapped'))`.
   */
  case class Subquery(name: SubqueryName, pattern: ObjectName, where: Option[QueryExp]) {
    def run(conn: MBeanServerConnection): Set[ObjectName] =
      conn.queryNames(pattern, where.orNull).asScala.toSet
  }

  /**
   * The name of a property in a `javax.management.ObjectName`. For instance,
   * the `ObjectName` "java.nio:type=BufferPool,name=direct" has two properties, `type`,
   * which has a value of `"BufferPool"`, and `name`, which has a value of `"direct"`.
   */
  case class ObjectNameKey(key: String) {
    def get(name: ObjectName): Option[String] = ???
  }

  /**
   * The name of an attribute of a bean. Example: {{{
   *
   * java.nio:type=BufferPool,name=mapped
   * ------------------------------------
   * Name: mapped
   * Count: 0
   * TotalCapacity: 0
   * MemoryUsed: 0
   * ObjectName: java.nio:type=BufferPool,name=mapped*
   *
   * }}}
   *
   * Has the following attributes: "Name", "Count", "TotalCapacity", ...
   */
  case class AttributeName(name: String) {
    def get(result: Result): Option[AnyRef] = result.attributes.get(this)
  }

  /**
   * A single MBean result. Example: {{{
   *
   * java.nio:type=BufferPool,name=mapped
   * ------------------------------------
   * Name: mapped
   * Count: 0
   * TotalCapacity: 0
   * MemoryUsed: 0
   * ObjectName: java.nio:type=BufferPool,name=mapped*
   *
   * }}}
   *
   * The `ObjectName` is `"java.nio:type=BufferPool,name=mapped"`, and the
   * map of attributes will be: `Map(AttributeName("Name") -> "mapped", ...)`
   */
  case class Result(name: ObjectName,
                    attributes: Map[AttributeName,AnyRef]) {

    /** Extract a property from the `ObjectName` for this `Result`. */
    def getNameProperty(k: ObjectNameKey): Option[String] =
      Option(name.getKeyProperty(k.key))

    /** Extract an attribute value for the given attribute name. */
    def getAttribute(k: AttributeName): Option[AnyRef] =
      attributes.get(k)
  }

  // use foo:type to refer to an ObjectName key
  // mbeans 'java.lang:*' where :type = 'Memory'

  import scalaz.std.option._
  import scalaz.Monad
  val O = Monad[Option]

  case class Row(subqueries: Map[SubqueryName, Result]) {
    def get(sub: SubqueryName): Option[Result] = subqueries.get(sub)
  }

  case class Field[A](extract: Row => Option[A]) {

    // going to hold off on making Field a Monad, restricting its algebra to what
    // is provided here, in case we want to provide an initial encoding of this type
    // for sending to the server

    /** Cast this `Field[A]` to a `Field[B]`. */
    def as[B](implicit B: Manifest[B]): Field[B] =
      Field { row => this.extract(row) flatMap {
        case B(b) => Some(b)
        case _ => None
      }}

    /** If this field's value is any numeric type, convert it to a `BigDecimal`. */
    def asNumber: Field[BigDecimal] =
      Field { row => this.extract(row) flatMap {
        case bi: java.math.BigInteger => Some(BigDecimal(new BigInt(bi)))
        case sbi: scala.math.BigInt => Some(BigDecimal(sbi))
        case bd: java.math.BigDecimal => Some(BigDecimal(bd))
        case sbd: scala.math.BigDecimal => Some(sbd)
        case s: java.lang.Short => Some(BigDecimal((s: Short): Int))
        case i: java.lang.Integer => Some(BigDecimal(i))
        case l: java.lang.Long => Some(BigDecimal(l))
        case f: java.lang.Float => Some(BigDecimal((f: Float): Double))
        case d: java.lang.Double => Some(BigDecimal(d))
        case _ => None
      }}

    def +[N](f: Field[A])(implicit toN: A =:= N, N: Numeric[N]): Field[N] =
      Field { row => O.apply2(this.extract(row).map(toN), f.extract(row).map(toN))(N.plus) }

    def -[N](f: Field[A])(implicit toN: A =:= N, N: Numeric[N]): Field[N] =
      Field { row => O.apply2(this.extract(row).map(toN), f.extract(row).map(toN))(N.minus) }

    def *[N](f: Field[A])(implicit toN: A =:= N, N: Numeric[N]): Field[N] =
      Field { row => O.apply2(this.extract(row).map(toN), f.extract(row).map(toN))(N.times) }

    def /[N](f: Field[A])(implicit toN: A =:= N, N: Fractional[N]): Field[N] =
      Field { row => O.apply2(this.extract(row).map(toN), f.extract(row).map(toN))(N.div) }

    def negate[N](implicit toN: A =:= N, N: Numeric[N]): Field[N] =
      Field { row => this.extract(row).map(toN).map(N.negate) }

    def &&(f: Field[A])(implicit toBool: A =:= Boolean): Field[Boolean] =
      Field { row => O.apply2(this.extract(row).map(toBool), f.extract(row).map(toBool))(_ && _) }
    def and(f: Field[A])(implicit toBool: A =:= Boolean): Field[Boolean] =
      this && f

    def ||(f: Field[A])(implicit toBool: A =:= Boolean): Field[Boolean] =
      Field { row => O.apply2(this.extract(row).map(toBool), f.extract(row).map(toBool))(_ || _) }
    def or(f: Field[A])(implicit toBool: A =:= Boolean): Field[Boolean] =
      this || f

    def xor(f: Field[A])(implicit toBool: A =:= Boolean): Field[Boolean] =
      Field { row => O.apply2(this.extract(row).map(toBool), f.extract(row).map(toBool))((a,b) => (a && !b) || (!a && b)) }

    /** Returns true if this field's value is less than `f`. */
    def <(f: Field[A])(implicit A: Ordering[A]): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(A.lt) }

    /** Returns true if this field's value is greater than `f`. */
    def >(f: Field[A])(implicit A: Ordering[A]): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(A.gt) }

    /** Returns true if this field's value is greater than or equal to `f`. */
    def >=(f: Field[A])(implicit A: Ordering[A]): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(A.gteq) }

    /** Returns true if this field's value is less than or equal to `f`. */
    def <=(f: Field[A])(implicit A: Ordering[A]): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(A.lteq) }

    /** Returns true if this field's value is equal to `f`, using Object equality. */
    def ===(f: Field[A]): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(_ == _) }

    /** Returns true if this field's value is `>= lower` and `<= upper`. */
    def between(lower: Field[A], upper: Field[A])(implicit A: Ordering[A]): Field[Boolean] =
      Field { row => O.apply3(this.extract(row), lower.extract(row), upper.extract(row))(
        (v,low,hi) => A.lteq(v, hi) && A.gteq(v, low))
      }

    /** Returns true if this field's value matches the value of any of the provided fields. */
    def in(fs: Field[A]*): Field[Boolean] =
      Field { row =>
        this.extract(row).map { target => fs.flatMap(f => f.extract(row).toList).exists(_ == target) }
      }

    /** Returns true if this field's value is prefixed by the value of `f`. */
    def startsWith(f: Field[A])(implicit toS: A =:= String): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(_ startsWith _) }

    /** Returns true if this field's value is has a suffix of the value of `f`. */
    def endsWith(f: Field[A])(implicit toS: A =:= String): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))(_ endsWith _) }

    /** Returns true if this field's value is a substring of the value of `f`. */
    def isSubstringOf(f: Field[A])(implicit toS: A =:= String): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), f.extract(row))((sub,word) => word.indexOf(sub) >= 0) }

    /** Returns true if this field's value matches the glob pattern given by `glob`. */
    def like(glob: Field[A])(implicit toS: A =:= String): Field[Boolean] =
      Field { row => O.apply2(this.extract(row), glob.extract(row))((word, pat) => compileGlob(pat)(word)) }
  }

  object Field {

    /** A field which looks up the given attribute in the given subquery. */
    def attribute(subquery: SubqueryName, key: AttributeName): Field[AnyRef] =
      Field { row => row.get(subquery).map(_.attributes.get(key)) }

    /** A field which looks up the given `ObjectName` property in the given subquery. */
    def nameProperty(subquery: SubqueryName, key: ObjectNameKey): Field[String] =
      Field { row => row.get(subquery).flatMap(_.getNameProperty(key)) }

    /** Promote an `A` to a `Field[A]`. */
    def literal[A](a: A): Field[A] = Field { _ => Some(a) }

    def path(subquery: SubqueryName, path: List[ObjectNameKey \/ AttributeName]): Field[AnyRef] =
      // we assume that the full bean is loaded, and any sub-beans have been converted to nested Map[AttributeName,AnyRef]
      Field { row =>
        row.get(subquery).flatMap { res =>
          def go(cur: Option[AnyRef], path: List[ObjectNameKey \/ AttributeName]): Option[AnyRef] = cur match {
            case None => None
            case Some(ref) => path match {
              case List() => Some(ref)
              case h :: t => h.fold(
                oname => ref match { case r: Result => go(r.getNameProperty(oname), t); case _ => None },
                aname => ref match {
                  case r: Result => go(r.getAttribute(aname), t)
                  case r: Map[AttributeName @unchecked, AnyRef @unchecked] => go(r.get(aname), t)
                }
              )
            }
          }
          go(Some(res), path)
        }
      }
  }

  // very quick and dirty - does not cover all possible globs
  private def compileGlob(glob: String): String => Boolean = {
    // convert to a regex pattern
    val out = new StringBuilder("^")
    glob.foreach { char =>
      char match {
        case '*' => out append ".*"
        case '?' => out append "."
        case '.' => out append "\\."
        case '\\' => out append "\\\\"
        case _ => out append char
      }
    }
    out append '$'
    val P = out.toString.r
    s => s match { case P() => true; case _ => false }
  }

  case class Results(subqueries: Map[SubqueryName, Seq[Result]]) {
    def names: Set[ObjectName] = subqueries.values.flatMap(results => results.map(_.name)).toSet
    def results: Seq[Result] = subqueries.values.flatten.toSeq
  }
}
