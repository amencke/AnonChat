name := "AnonChat"

version := "0.1"

scalaVersion := "2.13.6"

idePackagePrefix := Some("com.aqualung.anonchat")

val AkkaVersion     = "2.6.15"
val AkkaHttpVersion = "10.2.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"         % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream"              % AkkaVersion,
  "com.typesafe.akka" %% "akka-http"                % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"     % AkkaHttpVersion
)

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
