fs = require "fs"
system = require "system"

system.stdin.readLineAsync (err, data) ->
  console.log "system.stdin:", err, data
  return

console.log "Hello"

fs.readAsync "Makefile", (err, data) ->
  console.log "Makefile:", err, data.substring 0, 12
  return

console.log "There"

fs.openAsync "third-party.txt", "r", (err, f) ->
  if err?
    console.log "third-party.txt:", err
    return
  f.readAsync (err, data) ->
    f.close()
    console.log "third-party.txt:", err, data.substring 0, 12
    return
  return

console.log "!"