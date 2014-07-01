package actors


class NorvegianAirlines extends BaseFetcherActor {
	def receive = {
		case "1" => 
		println("BAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1")
		
		execWithTimeout(Seq("ls","/etc"))

		sender ! "1"

	}
}