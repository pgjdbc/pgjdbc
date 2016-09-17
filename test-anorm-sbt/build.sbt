import play.PlayImport.PlayKeys._

name := "pgbug"

organization := "io.flow"

scalaVersion in ThisBuild := "2.11.8"

resolvers := (Resolver.mavenLocal +: resolvers.value)

lazy val root = project
  .in(file("."))
  .enablePlugins(PlayScala)
  .settings(
    libraryDependencies ++= Seq(
      jdbc,
      "com.typesafe.play" %% "anorm" % "2.5.2",
      "org.postgresql" % "postgresql" % "9.4.1210",
      "org.scalatestplus" %% "play" % "1.4.0" % "test"
    )
)

