package cats
package data

import cats.functor.{ Contravariant, Bifunctor, Profunctor }
import cats.syntax.either._

/**
  * Represents a stateful computation in a context `F[_]`, over state `S`, with an initial environment `E`, an accumulated log `L` and a result `A`.
  *
  * In other words, it is a pre-baked stack of `[[ReaderT]][F, E, A]`, `[[WriterT]][F, L, A]`
  * and `[[StateT]][F, S, A]`.
  */
final class RWST[F[_], E, S, L, A](val runF: F[(E, S) => F[(L, S, A)]]) extends Serializable {

  /**
    * Modify the initial environment using `f`.
    */
  def contramap[E0](f: E0 => E)(implicit F: Functor[F]): RWST[F, E0, S, L, A] =
    RWST.applyF {
      F.map(runF) { rwsa =>
        (e0: E0, s: S) => rwsa(f(e0), s)
      }
    }

  /**
    * Alias for [[contramap]].
    */
  def local[EE](f: EE => E)(implicit F: Functor[F]): RWST[F, EE, S, L, A] =
    contramap(f)

  /**
    * Modify the result of the computation using `f`.
    */
  def map[B](f: A => B)(implicit F: Functor[F]): RWST[F, E, S, L, B] =
    transform { (l, s, a) => (l, s, f(a)) }

  /**
    * Modify the written log value using `f`.
    */
  def mapWritten[LL](f: L => LL)(implicit F: Functor[F]): RWST[F, E, S, LL, A] =
    transform { (l, s, a) => (f(l), s, a) }

  /**
    * Combine this computation with `rwsb` using `fn`. The state will be be threaded
    * through the computations and the log values will be combined.
    */
  def map2[B, Z](rwsb: RWST[F, E, S, L, B])(fn: (A, B) => Z)(implicit F: FlatMap[F], L: Semigroup[L]): RWST[F, E, S, L, Z] =
    RWST.applyF {
      F.map2(runF, rwsb.runF) { (rwsfa, rwsfb) =>
        (e: E, s0: S) =>
          F.flatMap(rwsfa(e, s0)) { case (la, sa, a) =>
            F.map(rwsfb(e, sa)) { case (lb, sb, b) =>
              (L.combine(la, lb), sb, fn(a, b))
            }
          }
      }
    }

  /**
    * Like [[map2]], but allows controlling the evaluation strategy.
    */
  def map2Eval[B, Z](fb: Eval[RWST[F, E, S, L, B]])(fn: (A, B) => Z)(implicit F: FlatMap[F], L: Semigroup[L]): Eval[RWST[F, E, S, L, Z]] =
    F.map2Eval(runF, fb.map(_.runF)) { (rwsfa, rwsfb) =>
      (e: E, s0: S) =>
      F.flatMap(rwsfa(e, s0)) { case (la, sa, a) =>
        F.map(rwsfb(e, sa)) { case (lb, sb, b) =>
          (L.combine(la, lb), sb, fn(a, b))
        }
      }
    }.map(RWST.applyF(_))

  /**
    * Modify the result of the computation by feeding it into `f`, threading the state
    * through the resulting computation and combining the log values.
    */
  def flatMap[B](f: A => RWST[F, E, S, L, B])(implicit F: FlatMap[F], L: Semigroup[L]): RWST[F, E, S, L, B] =
    RWST.applyF {
      F.map(runF) { rwsfa =>
        (e: E, s0: S) =>
          F.flatMap(rwsfa(e, s0)) { case (la, sa, a) =>
            F.flatMap(f(a).runF) { rwsfb =>
              F.map(rwsfb(e, sa)) { case (lb, sb, b) =>
                (L.combine(la, lb), sb, b)
              }
            }
          }
      }
    }

  /**
    * Like [[map]], but allows the mapping function to return an effectful value.
    */
  def flatMapF[B](faf: A => F[B])(implicit F: FlatMap[F]): RWST[F, E, S, L, B] =
    RWST.applyF {
      F.map(runF) { rwsfa =>
        (e: E, s: S) =>
          F.flatMap(rwsfa(e, s)) { case (l, s, a) =>
            F.map(faf(a))((l, s, _))
          }
      }
    }

  /**
    * Transform the resulting log, state and value using `f`.
    */
  def transform[LL, B](f: (L, S, A) => (LL, S, B))(implicit F: Functor[F]): RWST[F, E, S, LL, B] =
    RWST.applyF {
      F.map(runF) { rwsfa =>
        (e: E, s: S) => F.map(rwsfa(e, s)) { case (l, s, a) =>
          val (ll, sb, b) = f(l, s, a)
          (ll, sb, b)
        }
      }
    }

  /**
    * Like [[transform]], but allows the context to change from `F` to `G`.
    */
  def transformF[G[_], LL, B](f: F[(L, S, A)] => G[(LL, S, B)])(implicit F: Monad[F], G: Applicative[G]): RWST[G, E, S, LL, B] =
    RWST.apply((e, s) => f(run(e, s)))


  /**
    * Modify the resulting state.
    */
  def modify(f: S => S)(implicit F: Functor[F]): RWST[F, E, S, L, A] =
    transform { (l, s, a) => (l, f(s), a) }

  /**
    * Inspect a value from the input state, without modifying the state.
    */
  def inspect[B](f: S => B)(implicit F: Functor[F]): RWST[F, E, S, L, B] =
    transform { (l, s, a) => (l, s, f(s)) }

  /**
    * Get the input state, without modifying it.
    */
  def get(implicit F: Functor[F]): RWST[F, E, S, L, S] =
    inspect(identity)

  /**
    * Add a value to the log.
    */
  def tell(l: L)(implicit F: Functor[F], L: Semigroup[L]): RWST[F, E, S, L, A] =
    mapWritten(L.combine(_, l))

  /**
    * Retrieve the value written to the log.
    */
  def written(implicit F: Functor[F]): RWST[F, E, S, L, L] =
    transform { (l, s, a) => (l, s, l) }

  /**
    * Clear the log.
    */
  def reset(implicit F: Functor[F], L: Monoid[L]): RWST[F, E, S, L, A] =
    mapWritten(_ => L.empty)

  /**
    * Run the computation using the provided initial environment and state.
    */
  def run(env: E, initial: S)(implicit F: Monad[F]): F[(L, S, A)] =
    F.flatMap(runF)(_.apply(env, initial))

  /**
    * Run the computation using the provided environment and an empty state.
    */
  def runEmpty(env: E)(implicit F: Monad[F], S: Monoid[S]): F[(L, S, A)] =
    run(env, S.empty)

  /**
    * Like [[run]], but discards the final state and log.
    */
  def runA(env: E, initial: S)(implicit F: Monad[F]): F[A] =
    F.map(run(env, initial))(_._3)

  /**
    * Like [[run]], but discards the final value and log.
    */
  def runS(env: E, initial: S)(implicit F: Monad[F]): F[S] =
    F.map(run(env, initial))(_._2)

  /**
    * Like [[run]], but discards the final state and value.
    */
  def runL(env: E, initial: S)(implicit F: Monad[F]): F[L] =
    F.map(run(env, initial))(_._1)

  /**
    * Like [[runEmpty]], but discards the final state and log.
    */
  def runEmptyA(env: E)(implicit F: Monad[F], S: Monoid[S]): F[A] =
    runA(env, S.empty)

  /**
    * Like [[runEmpty]], but discards the final value and log.
    */
  def runEmptyS(env: E)(implicit F: Monad[F], S: Monoid[S]): F[S] =
    runS(env, S.empty)

  /**
    * Like [[runEmpty]], but discards the final state and value.
    */
  def runEmptyL(env: E)(implicit F: Monad[F], S: Monoid[S]): F[L] =
    runL(env, S.empty)
}

object RWST extends RWSTInstances {
  /**
    * Construct a new computation using the provided function.
    */
  def apply[F[_], E, S, L, A](runF: (E, S) => F[(L, S, A)])(implicit F: Applicative[F]): RWST[F, E, S, L, A] =
    new RWST(F.pure(runF))

  /**
    * Like [[apply]], but using a function in a context `F`.
    */
  def applyF[F[_], E, S, L, A](runF: F[(E, S) => F[(L, S, A)]]): RWST[F, E, S, L, A] =
    new RWST(runF)

  /**
    * Return `a` and an empty log without modifying the input state.
    */
  def pure[F[_], E, S, L, A](a: A)(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, A] =
    RWST((_, s) => F.pure((L.empty, s, a)))

  /**
    * Return an effectful `a` and an empty log without modifying the input state.
    */
  def lift[F[_], E, S, L, A](fa: F[A])(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, A] =
    RWST((_, s) => F.map(fa)((L.empty, s, _)))

  /**
    * Inspect a value from the input state, without modifying the state.
    */
  def inspect[F[_], E, S, L, A](f: S => A)(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, A] =
    RWST((_, s) => F.pure((L.empty, s, f(s))))

  /**
    * Like [[inspect]], but using an effectful function.
    */
  def inspectF[F[_], E, S, L, A](f: S => F[A])(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, A] =
    RWST((_, s) => F.map(f(s))((L.empty, s, _)))

  /**
    * Modify the input state using `f`.
    */
  def modify[F[_], E, S, L](f: S => S)(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, Unit] =
    RWST((_, s) => F.pure((L.empty, f(s), ())))

  /**
    * Like [[modify]], but using an effectful function.
    */
  def modifyF[F[_], E, S, L](f: S => F[S])(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, Unit] =
    RWST((_, s) => F.map(f(s))((L.empty, _, ())))

  /**
    * Return the input state without modifying it.
    */
  def get[F[_], E, S, L](implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, S] =
    RWST((_, s) => F.pure((L.empty, s, s)))

  /**
    * Set the state to `s`.
    */
  def set[F[_], E, S, L](s: S)(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, Unit] =
    RWST((_, _) => F.pure((L.empty, s, ())))

  /**
    * Like [[set]], but using an effectful `S` value.
    */
  def setF[F[_], E, S, L](fs: F[S])(implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, Unit] =
    RWST((_, _) => F.map(fs)((L.empty, _, ())))

  /**
    * Get the provided environment, without modifying the input state.
    */
  def ask[F[_], E, S, L](implicit F: Applicative[F], L: Monoid[L]): RWST[F, E, S, L, E] =
    RWST((e, s) => F.pure((L.empty, s, e)))

  /**
    * Add a value to the log, without modifying the input state.
    */
  def tell[F[_], E, S, L](l: L)(implicit F: Applicative[F]): RWST[F, E, S, L, Unit] =
    RWST((_, s) => F.pure((l, s, ())))

  /**
    * Like [[tell]], but using an effectful `L` value.
    */
  def tellF[F[_], E, S, L](fl: F[L])(implicit F: Applicative[F]): RWST[F, E, S, L, Unit] =
    RWST((_, s) => F.map(fl)((_, s, ())))
}

/**
  * Convenience functions for RWS.
  */
private[data] abstract class RWSFunctions {
  /**
    * Return `a` and an empty log without modifying the input state.
    */
  def apply[E, S, L: Monoid, A](f: (E, S) => (L, S, A)): RWS[E, S, L, A] =
    RWST.applyF(Now((e, s) => Now(f(e, s))))

  /**
    * Return `a` and an empty log without modifying the input state.
    */
  def pure[E, S, L: Monoid, A](a: A): RWS[E, S, L, A] =
    RWST.pure(a)

  /**
    * Modify the input state using `f`.
    */
  def modify[E, S, L: Monoid](f: S => S): RWS[E, S, L, Unit] =
    RWST.modify(f)

  /**
    * Inspect a value from the input state, without modifying the state.
    */
  def inspect[E, S, L: Monoid, T](f: S => T): RWS[E, S, L, T] =
    RWST.inspect(f)

  /**
    * Return the input state without modifying it.
    */
  def get[E, S, L: Monoid]: RWS[E, S, L, S] =
    RWST.get

  /**
    * Set the state to `s`.
    */
  def set[E, S, L: Monoid](s: S): RWS[E, S, L, Unit] =
    RWST.set(s)

  /**
    * Get the provided environment, without modifying the input state.
    */
  def ask[E, S, L](implicit L: Monoid[L]): RWS[E, S, L, E] =
    RWST.ask

  /**
    * Add a value to the log, without modifying the input state.
    */
  def tell[E, S, L](l: L): RWS[E, S, L, Unit] =
    RWST.tell(l)
}

private[data] sealed trait RWSTInstances extends RWSTInstances1 {
  implicit def catsDataMonadStateForRWST[F[_], E, S, L](implicit F0: Monad[F], L0: Monoid[L]): MonadState[RWST[F, E, S, L, ?], S] =
    new RWSTMonadState[F, E, S, L] {
      implicit def F: Monad[F] = F0
      implicit def L: Monoid[L] = L0
    }

  implicit def catsDataLiftForRWST[E, S, L](implicit L0: Monoid[L]): TransLift.Aux[RWST[?[_], E, S, L, ?], Applicative] =
    new RWSTTransLift[E, S, L] {
      implicit def L: Monoid[L] = L0
    }
}

private[data] sealed trait RWSTInstances1 extends RWSTInstances2 {
  implicit def catsDataMonadCombineForRWST[F[_], E, S, L](implicit F0: MonadCombine[F], L0: Monoid[L]): MonadCombine[RWST[F, E, S, L, ?]] =
    new RWSTMonadCombine[F, E, S, L] {
      implicit def F: MonadCombine[F] = F0
      implicit def L: Monoid[L] = L0
    }
}

private[data] sealed trait RWSTInstances2 extends RWSTInstances3 {
  implicit def catsDataMonadErrorForRWST[F[_], E, S, L, R](implicit F0: MonadError[F, R], L0: Monoid[L]): MonadError[RWST[F, E, S, L, ?], R] =
    new RWSTMonadError[F, E, S, L, R] {
      implicit def F: MonadError[F, R] = F0
      implicit def L: Monoid[L] = L0
    }

  implicit def catsDataSemigroupKForRWST[F[_], E, S, L](implicit F0: Monad[F], G0: SemigroupK[F]): SemigroupK[RWST[F, E, S, L, ?]] =
    new RWSTSemigroupK[F, E, S, L] {
      implicit def F: Monad[F] = F0
      implicit def G: SemigroupK[F] = G0
    }
}

private[data] sealed trait RWSTInstances3 extends RWSTInstances4 {
  implicit def catsDataMonadReaderForRWST[F[_], E, S, L](implicit F0: Monad[F], L0: Monoid[L]): MonadReader[RWST[F, E, S, L, ?], E] = new RWSTMonadReader[F, E, S, L] {
    implicit def F: Monad[F] = F0
    implicit def L: Monoid[L] = L0
  }
}

private[data] sealed trait RWSTInstances4 extends RWSTInstances5 {
  implicit def catsDataMonadWriterForRWST[F[_], E, S, L](implicit F0: Monad[F], L0: Monoid[L]): MonadWriter[RWST[F, E, S, L, ?], L] = new RWSTMonadWriter[F, E, S, L] {
    implicit def F: Monad[F] = F0
    implicit def L: Monoid[L] = L0
  }
}

private[data] sealed trait RWSTInstances5 extends RWSTInstances6 {
  implicit def catsDataMonadForRWST[F[_], E, S, L](implicit F0: Monad[F], L0: Monoid[L]): Monad[RWST[F, E, S, L, ?]] =
    new RWSTMonad[F, E, S, L] {
      implicit def F: Monad[F] = F0
      implicit def L: Monoid[L] = L0
    }
}

private[data] sealed trait RWSTInstances6 extends RWSTInstances7 {
  implicit def catsDataFunctorForRWST[F[_], E, S, L](implicit F0: Functor[F]): Functor[RWST[F, E, S, L, ?]] =
    new RWSTFunctor[F, E, S, L] {
      implicit def F: Functor[F] = F0
    }
}

private[data] sealed trait RWSTInstances7 extends RWSTInstances8 {
  implicit def catsDataContravariantForRWST[F[_], S, L, A](implicit F0: Functor[F]): Contravariant[RWST[F, ?, S, L, A]] =
    new RWSTContravariant[F, S, L, A] {
      implicit def F: Functor[F] = F0
    }
}

private[data] sealed trait RWSTInstances8 extends RWSTInstances9 {
  implicit def catsDataBifunctorForRWST[F[_], E, S](implicit F0: Functor[F]): Bifunctor[RWST[F, E, S, ?, ?]] =
    new RWSTBifunctor[F, E, S] {
      implicit def F: Functor[F] = F0
    }
}

private[data] sealed trait RWSTInstances9 {
  implicit def catsDataProfunctorForRWST[F[_], S, L](implicit F0: Functor[F]): Profunctor[RWST[F, ?, S, L, ?]] =
    new RWSTProfunctor[F, S, L] {
      implicit def F: Functor[F] = F0
    }
}

private[data] sealed trait RWSTFunctor[F[_], E, S, L] extends Functor[RWST[F, E, S, L, ?]] {
  implicit def F: Functor[F]

  def map[A, B](fa: RWST[F, E, S, L, A])(f: A => B): RWST[F, E, S, L, B] =
    fa.map(f)
}

private[data] sealed trait RWSTContravariant[F[_], S, L, T] extends Contravariant[RWST[F, ?, S, L, T]] {
  implicit def F: Functor[F]

  override def contramap[A, B](fa: RWST[F, A, S, L, T])(f: B => A): RWST[F, B, S, L, T] =
    fa.contramap(f)
}

private[data] sealed trait RWSTBifunctor[F[_], E, S] extends Bifunctor[RWST[F, E, S, ?, ?]] {
  implicit def F: Functor[F]

  override def bimap[A, B, C, D](fab: RWST[F, E, S, A, B])(f: A => C, g: B => D): RWST[F, E, S, C, D] = fab.mapWritten(f).map(g)
}

private[data] sealed trait RWSTProfunctor[F[_], S, L] extends Profunctor[RWST[F, ?, S, L, ?]] {
  implicit def F: Functor[F]

  override def dimap[A, B, C, D](fab: RWST[F, A, S, L, B])(f: C => A)(g: B => D): RWST[F, C, S, L, D] =
    fab.contramap(f).map(g)
}

private[data] sealed trait RWSTMonad[F[_], E, S, L] extends Monad[RWST[F, E, S, L, ?]] {
  implicit def F: Monad[F]
  implicit def L: Monoid[L]

  def pure[A](a: A): RWST[F, E, S, L, A] =
    RWST.pure(a)

  def flatMap[A, B](fa: RWST[F, E, S, L, A])(f: A => RWST[F, E, S, L, B]): RWST[F, E, S, L, B] =
    fa.flatMap(f)

  def tailRecM[A, B](initA: A)(f: A => RWST[F, E, S, L, Either[A, B]]): RWST[F, E, S, L, B] =
    RWST { (e, initS) =>
      F.tailRecM((L.empty, initS, initA)) { case (currL, currS, currA) =>
        F.map(f(currA).run(e, currS)) { case (nextL, nextS, ab) =>
          ab.bimap((L.combine(currL, nextL), nextS, _), (L.combine(currL, nextL), nextS, _))
        }
      }
    }

  override def map[A, B](fa: RWST[F, E, S, L, A])(f: A => B): RWST[F, E, S, L, B] =
    fa.map(f)

  override def map2[A, B, Z](fa: RWST[F, E, S, L, A], fb: RWST[F, E, S, L, B])(f: (A, B) => Z): RWST[F, E, S, L, Z] =
    fa.map2(fb)(f)

  override def map2Eval[A, B, Z](fa: RWST[F, E, S, L, A], fb: Eval[RWST[F, E, S, L, B]])(f: (A, B) => Z): Eval[RWST[F, E, S, L, Z]] =
    fa.map2Eval(fb)(f)
}

private[data] sealed trait RWSTMonadState[F[_], E, S, L] extends MonadState[RWST[F, E, S, L, ?], S] with RWSTMonad[F, E, S, L] {
  lazy val get: RWST[F, E, S, L, S] = RWST.get

  def set(s: S): RWST[F, E, S, L, Unit] = RWST.set(s)
}

private[data] sealed trait RWSTTransLift[E, S, L] extends TransLift[RWST[?[_], E, S, L, ?]] {
  implicit def L: Monoid[L]
  type TC[F[_]] = Applicative[F]

  def liftT[F[_], A](fa: F[A])(implicit F: Applicative[F]): RWST[F, E, S, L, A] =
    RWST.lift(fa)
}

private[data] sealed trait RWSTSemigroupK[F[_], E, S, L] extends SemigroupK[RWST[F, E, S, L, ?]] {
  implicit def F: Monad[F]
  implicit def G: SemigroupK[F]

  def combineK[A](x: RWST[F, E, S, L, A], y: RWST[F, E, S, L, A]): RWST[F, E, S, L, A] =
    RWST { (e, s) =>
      G.combineK(x.run(e, s), y.run(e, s))
    }
}

private[data] sealed trait RWSTMonadCombine[F[_], E, S, L] extends MonadCombine[RWST[F, E, S, L, ?]] with RWSTMonad[F, E, S, L]
    with RWSTSemigroupK[F, E, S, L] with RWSTTransLift[E, S, L] {
  implicit def F: MonadCombine[F]
  override def G: MonadCombine[F] = F

  def empty[A]: RWST[F, E, S, L, A] = liftT[F, A](F.empty[A])
}

private[data] sealed trait RWSTMonadError[F[_], E, S, L, R] extends RWSTMonad[F, E, S, L] with MonadError[RWST[F, E, S, L, ?], R] {
  implicit def F: MonadError[F, R]

  def raiseError[A](r: R): RWST[F, E, S, L, A] = RWST.lift(F.raiseError(r))

  def handleErrorWith[A](fa: RWST[F, E, S, L, A])(f: R => RWST[F, E, S, L, A]): RWST[F, E, S, L, A] =
    RWST { (e, s) =>
      F.handleErrorWith(fa.run(e, s))(r => f(r).run(e, s))
    }
}

private[data] sealed trait RWSTMonadReader[F[_], E, S, L] extends RWSTMonad[F, E, S, L] with MonadReader[RWST[F, E, S, L, ?], E] {
  val ask: RWST[F, E, S, L, E] = RWST.ask

  def local[A](f: E => E)(fa: RWST[F, E, S, L, A]): RWST[F, E, S, L, A] = fa contramap f
}

private[data] sealed trait RWSTMonadWriter[F[_], E, S, L] extends RWSTMonad[F, E, S, L] with MonadWriter[RWST[F, E, S, L, ?], L] {
  def writer[A](aw: (L, A)): RWST[F, E, S, L, A] =
    RWST((_, s) => F.pure((aw._1, s, aw._2)))

  def listen[A](fa: RWST[F, E, S, L, A]): RWST[F, E, S, L, (L, A)] =
    fa.transform { (l, s, a) =>
      (l, s, (l, a))
    }

  def pass[A](fa: RWST[F, E, S, L, (L => L, A)]): RWST[F, E, S, L, A] =
    fa.transform { case (l, s, (fl, a)) =>
      (fl(l), s, a)
    }
}
