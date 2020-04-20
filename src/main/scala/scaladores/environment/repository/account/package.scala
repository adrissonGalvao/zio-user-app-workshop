package scaladores.environment.repository

import java.time.OffsetDateTime
import java.util.UUID

import scaladores.domain.Account
import zio.clock._
import zio.{Has, ZIO, ZLayer}
import zio.interop.catz._
import io.scalaland.chimney.dsl._
import scaladores.failure.repository.AccountRepositoryFailure.{
  AccountRepositoryInsertFailure,
  AccountRepositoryNotFound
}
package object account {

  type AccountRepository = Has[AccountRepository.Service]

  object AccountRepository {

    type LayerEnv = Clock with DBTransactor

    trait Service {

      def create(account: Account): ZIO[Any, Throwable, Unit]

      def findByUuid(uuid: UUID): ZIO[Any, Throwable, Account]

      def findByDocument(document: String): ZIO[Any, Throwable, Account]

    }

    val live: ZLayer[LayerEnv, Nothing, AccountRepository] =
      ZLayer.fromFunction[LayerEnv, AccountRepository.Service] { env =>
        import doobie.implicits._
        import doobie.implicits.javatime._
        import doobie.postgres.implicits._

        new Service {

          private case class AccountRow(uuid: UUID, document: String, createdAt: OffsetDateTime)

          override def create(account: Account): ZIO[Any, Throwable, Unit] = {

            def create(account: Account, createdAt: OffsetDateTime) =
              sql"""INSERT INTO account (uuid, document, created_at) VALUES (${account.uuid}::uuid, ${account.document}, $createdAt) """.update

            val pipeline = for {
              createdAt <- currentDateTime
              xa        <- transactor
              _         <- create(account, createdAt).run.transact(xa)
            } yield ()

            pipeline
              .mapError(t => AccountRepositoryInsertFailure(t.getMessage))
              .provide(env)

          }

          override def findByUuid(uuid: UUID): ZIO[Any, Throwable, Account] = {

            sql""" | SELECT (
                   |  uuid,
                   |  document,
                   |  createdAt
                   | ) FROM account
                   | WHERE uuid = ${uuid}
                 """.stripMargin
              .query[AccountRow]
              .to[List]
              .transact(env.get[DBTransactor.Resource].xa)
              .flatMap {
                _.headOption match {
                  case None    => ZIO.fail(AccountRepositoryNotFound)
                  case Some(w) => ZIO.succeed(w.into[Account].transform)
                }
              }

          }

          override def findByDocument(document: String): ZIO[Any, Throwable, Account] = {
            sql"""
                 | SELECT (
                 |  uuid,
                 |  document,
                 |  createdAt
                 | ) FROM account
                 | WHERE document = ${document}
                 """.stripMargin
              .query[AccountRow]
              .to[List]
              .transact(env.get[DBTransactor.Resource].xa)
              .flatMap {
                _.headOption match {
                  case None    => ZIO.fail(AccountRepositoryNotFound)
                  case Some(w) => ZIO.succeed(w.into[Account].transform)
                }
              }
          }

        }

      }

  }

}
