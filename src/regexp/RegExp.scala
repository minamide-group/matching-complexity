package regexp

import scala.collection.mutable.Stack
import monad._
import Monad._
import transition.Morph._


sealed trait RegExp[A] {
  override def toString(): String = RegExp.toString(this)
  def derive[M[_]](a: A)(implicit m: Monad[M]): M[Option[RegExp[A]]] = RegExp.derive[M,A](this,a)
  def calcMorphs(): Seq[Morph[RegExp[A]]] = RegExp.calcMorphs(this)
  def calcGrowthRate(): Option[Int] = morphs2Graph(rename(calcMorphs())).calcAmbiguity()
}

case class ElemExp[A](a: A) extends RegExp[A]
case class EmptyExp[A]() extends RegExp[A]
case class EpsExp[A]() extends RegExp[A]
case class ConcatExp[A](r1: RegExp[A], r2: RegExp[A]) extends RegExp[A]
case class AltExp[A](r1: RegExp[A], r2: RegExp[A]) extends RegExp[A]
case class StarExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class PlusExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class OptionExp[A](r: RegExp[A], greedy: Boolean) extends RegExp[A]
case class DotExp[A]() extends RegExp[A]
case class CharClassExp(cs: Seq[CharClassElem], positive: Boolean) extends RegExp[Char] {
  val charSet = cs.flatMap(_.toCharSet()).toSet
}


object RegExp {
  def toString[A](r: RegExp[A]): String = {
    r match {
      case ElemExp(a) => a.toString
      case EmptyExp() => "∅"
      case EpsExp() => "ε"
      case ConcatExp(r1,r2) => s"(${r1}${r2})"
      case AltExp(r1,r2) => s"(${r1}|${r2})"
      case StarExp(r,greedy) => s"(${r})*${if (greedy) "" else "?"}"
      case PlusExp(r,greedy) => s"(${r})+${if (greedy) "" else "?"}"
      case OptionExp(r,greedy) => s"(${r})?${if (greedy) "" else "?"}"
      case DotExp() => "."
      case CharClassExp(es,positive) => s"[${if (positive) "" else "^"}${es.mkString}]"
    }
  }

  def derive[M[_],A](r: RegExp[A], a: A)(implicit m: Monad[M]): M[Option[RegExp[A]]] = {
    r match {
      case ElemExp(b) => if (a == b) m(Some(EpsExp())) else m.fail
      case EmptyExp() => m(Some(EmptyExp()))
      case EpsExp() => m(None)
      case ConcatExp(r1,r2) =>
        r1.derive[M](a) >>= {
          case Some(EpsExp()) => m(Some(r2))
          case Some(r) => m(Some(ConcatExp(r,r2)))
          case None => r2.derive[M](a)
        }
      case AltExp(r1,r2) =>
        r1.derive[M](a) ++ r2.derive[M](a)
      case StarExp(r,greedy) =>
        if (greedy) {
          (r.derive[M](a) >>= {
            case Some(EpsExp()) => m(Some(StarExp(r,true)))
            case Some(r1) => m(Some(ConcatExp(r1,StarExp(r,true))))
            case None => m(None)
          }: M[Option[RegExp[A]]]) ++ m(None)
        } else {
          (m(None): M[Option[RegExp[A]]]) ++ r.derive[M](a) >>= {
            case Some(EpsExp()) => m(Some(StarExp(r,false)))
            case Some(r1) => m(Some(ConcatExp(r1,StarExp(r,false))))
            case None => m(None)
          }
        }
      case PlusExp(r,greedy) =>
        if (greedy) {
          r.derive[M](a) >>= {
            case Some(EpsExp()) => m(Some(StarExp(r,true)))
            case Some(r1) => m(Some(ConcatExp(r1,StarExp(r,true))))
            case None => StarExp(r,true).derive[M](a)
          }
        } else {
          r.derive[M](a) >>= {
            case Some(EpsExp()) => m(Some(StarExp(r,false)))
            case Some(r1) => m(Some(ConcatExp(r1,StarExp(r,false))))
            case None => StarExp(r,false).derive[M](a)
          }
        }
      case OptionExp(r,greedy) =>
        if (greedy) {
          r.derive[M](a) ++ m(None)
        } else {
          (m(None): M[Option[RegExp[A]]]) ++ r.derive[M](a)
        }
      case DotExp() => m(Some(EpsExp()))
      case r @ CharClassExp(_,positive) => if (r.charSet.contains(a) ^ !positive) m(Some(EpsExp())) else m.fail
    }
  }

  def calcMorphs[A](r: RegExp[A]): Seq[Morph[RegExp[A]]] = {
    def getElems(r: RegExp[A]): Set[A] = {
      r match {
        case ElemExp(a) => Set(a)
        case EmptyExp() | EpsExp() | DotExp() => Set()
        case ConcatExp(r1,r2) => getElems(r1) | getElems(r2)
        case AltExp(r1,r2) => getElems(r1) | getElems(r2)
        case StarExp(r,_) => getElems(r)
        case PlusExp(r,_) => getElems(r)
        case OptionExp(r,_) => getElems(r)
        case r @ CharClassExp(_,_) => r.charSet
      }
    }

    val elems = getElems(r)
    var regExps = Set(r)
    val stack = Stack(r)
    var morphs = elems.map(_ -> Map[RegExp[A], Seq[RegExp[A]]]()).toMap
    while (stack.nonEmpty) {
      val r = stack.pop
      elems.foreach{ e =>
        val rs = r.derive[List](e).flatten
        morphs += (e -> (morphs(e) + (r -> r.derive[List](e).flatten)))
        val rsNew = rs.filterNot(regExps.contains)
        regExps |= rsNew.toSet
        stack.pushAll(rsNew)
      }
    }

    morphs.values.toList
  }
}


sealed trait CharClassElem {
  override def toString(): String = CharClassElem.toString(this)
  def toCharSet(): Set[Char] = CharClassElem.toCharSet(this)
}

case class SingleCharExp(c: Char) extends CharClassElem
case class RangeExp(start: Char, end: Char) extends CharClassElem


object CharClassElem {
  def toString(e: CharClassElem): String = {
    e match {
      case SingleCharExp(c) => c.toString
      case RangeExp(start, end) => s"${start}-${end}"
    }
  }

  def toCharSet(e: CharClassElem): Set[Char] = {
    e match {
      case SingleCharExp(c) => Set(c)
      case RangeExp(start, end) => (start to end).toSet
    }
  }
}
