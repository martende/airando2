
import sbt._
import Keys._
import play.Project._
import net.koofr.play2sprites.GenerateSprites._

object ApplicationBuild extends Build {

  val appName         = "airando2"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    "com.maxmind.geoip" % "geoip-api" % "1.2.13",
    "commons-collections" % "commons-collections" % "3.2.1"
    //"com.twitter" %% "util_2.10" % "6.16.0"
    //"com.twitter" % "util-core_2.10" % "6.1.0"
  )

  val main = play.Project(
    appName,
    appVersion,
    appDependencies,
    settings = Defaults.defaultSettings ++
        genSpritesSettings
  ).settings(
    spritesSrcImages <<= baseDirectory( (base: File) => base / "public/images/sprites" * "*.png" ),
    spritesDestImage <<= baseDirectory( (base: File) => base / "public/images/sprites.png" ),
    spritesCssSpritePath := "../images/sprites.png",
    spritesCssClassPrefix := "",
    spritesDestCss <<= baseDirectory( (base: File) => base / "app/assets/stylesheets/_sprites.less" ),

    resourceGenerators in Compile <<= (resourceGenerators in Compile, spritesGen) { (gens, spritesGen) =>
      spritesGen +: gens
    }
  )

}

