package utils

// Java
import java.io.File

// LRU
import com.twitter.util.LruMap
import org.apache.commons.collections.map.LRUMap

// MaxMind
import com.maxmind.geoip.{Location, LookupService}

case class IpLocation(
  countryCode: String,
  countryName: String,
  region: Option[String],
  city: Option[String],
  latitude: Float,
  longitude: Float,
  postalCode: Option[String],
  dmaCode: Option[Int],
  areaCode: Option[Int],
  metroCode: Option[Int]
  )

/**
 * Companion class contains a constructor
 * which takes a MaxMind Location class.
 */
object IpLocation {

  val empty = IpLocation("","",None,None,0,0,None,None,None,None)
  // Option-box a MaxMind Int, where MaxMind uses 0 to indicate None
  private val optionify: Int => Option[Int] = i => if (i == 0) None else Some(i)

  /**
   * Constructs an IpLocation from a MaxMind Location
   */
  def apply(loc: Location): IpLocation = IpLocation(
    countryCode = loc.countryCode,
    countryName = loc.countryName,
    region = Option(loc.region),
    city = Option(loc.city),
    latitude = loc.latitude,
    longitude = loc.longitude,
    postalCode = Option(loc.postalCode),
    dmaCode = optionify(loc.dma_code),
    areaCode = optionify(loc.area_code),
    metroCode = optionify(loc.metro_code)
    )
}

/**
 * Companion object to hold alternative constructors.
 *
 */
object IpGeo {

  /**
   * Alternative constructor taking a String rather than File
   */
  def apply(dbFile: String, memCache: Boolean = true, lruCache: Int = 10000) = {
    new IpGeo(new File(dbFile), memCache, lruCache)
  }
}

/**
 * IpGeo is a Scala wrapper around MaxMind's own LookupService Java class.
 *
 * Two main differences:
 *
 * 1. getLocation(ip: String) now returns an IpLocation
 *    case class, not a raw MaxMind Location
 * 2. IpGeo introduces an LRU cache to improve
 *    lookup performance
 *
 * Inspired by:
 * https://github.com/jt6211/hadoop-dns-mining/blob/master/src/main/java/io/covert/dns/geo/IpGeo.java
 */
class IpGeo(dbFile: File, memCache: Boolean = true, lruCache: Int = 10000) {

  // Initialise the cache
  //private val lru = if (lruCache > 0) new LruMap[String, Option[IpLocation]](lruCache) else null // Of type mutable.Map[String, Option[IpLocation]]

  private val lru = if (lruCache > 0) new LRUMap(lruCache) else null

  // Configure the lookup service
  private val options = if (memCache) LookupService.GEOIP_MEMORY_CACHE else LookupService.GEOIP_STANDARD
  private val maxmind = new LookupService(dbFile, options)

  /**
   * Returns the MaxMind location for this IP address
   * as an IpLocation, or None if MaxMind cannot find
   * the location.
   */
  def getLocation = if (lruCache <= 0) getLocationWithoutLruCache _ else getLocationWithLruCache _

  /**
   * Returns the MaxMind location for this IP address
   * as an IpLocation, or None if MaxMind cannot find
   * the location.
   *
   * This version does not use the LRU cache.
   */
  private def getLocationWithoutLruCache(ip: String): Option[IpLocation] =
    Option(maxmind.getLocation(ip)) map IpLocation.apply

  /**
   * Returns the MaxMind location for this IP address
   * as an IpLocation, or None if MaxMind cannot find
   * the location.
   *
   * This version uses and maintains the LRU cache.
   *
   * Don't confuse the LRU returning None (meaning that no
   * cache entry could be found), versus an extant cache entry
   * containing None (meaning that the IP address is unknown).
   */
  private def getLocationWithLruCache(ip: String): Option[IpLocation] = lru.get(ip) match {
    case null => 
      val loc = Option(maxmind.getLocation(ip)) map IpLocation.apply
      lru.put(ip, loc)
      loc
    case loc => loc.asInstanceOf[Option[IpLocation]] // In the LRU cache
  }
}