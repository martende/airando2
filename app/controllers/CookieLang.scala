package controllers

import play.api.mvc.{Action, Controller}
import play.api.i18n.Lang
import play.api.data._
import play.api.data.Forms._
import play.api.Logger
import play.api.Play.current

trait CookieLang extends Controller {

  val localeForm = Form("lang" -> nonEmptyText)

  val changeLocale = Action { implicit request =>
    val referrer = request.headers.get(REFERER).getOrElse(HOME_URL)
    localeForm.bindFromRequest.fold(
      errors => {
        Logger.debug("The lang can not be change error: " + errors.errors)
        BadRequest(referrer)
      },
      lang => {
        Logger.debug("Change user lang to : " + lang)
        Redirect(referrer).withLang(Lang(lang)) // TODO Check if the lang is handled by the application
      })
  }

  protected val HOME_URL = "/"
}