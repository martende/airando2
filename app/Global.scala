import play.api._

import play.api.libs.Files

import com.typesafe.config.ConfigFactory
import play.api.Play.current 

import model._

object Global extends GlobalSettings {

  override def onStart(app: Application) {

    Logger.info(s"Load configuration2 devMode=${play.api.Play.isDev} testMode=${play.api.Play.isTest}")

    prepareInitialData(app)

    Logger.info(s"Configuration successfully loaded")
  }

  override def onLoadConfig(config: Configuration, path: java.io.File, classloader: ClassLoader, mode: Mode.Mode): Configuration = {
    val modeSpecificConfig = config ++ Configuration(ConfigFactory.load(s"application.local.conf"))
    super.onLoadConfig(modeSpecificConfig, path, classloader, mode)
  }


    

  def prepareInitialData(app:Application) {
  	
    Airports

  }
}


