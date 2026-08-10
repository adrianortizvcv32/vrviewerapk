// ═════════════════════════════════════════════════════════════════
//  IMPORTANTE: no tengo el contenido actual de OscSender.kt (o el
//  archivo que arma el paquete UDP hacia el driver), así que NO
//  puedo darte el archivo completo sin arriesgarme a romper tu
//  handshake de auth (CVRHELLO / CHLG / RESP / TOKN con HMAC-SHA256)
//  que ya funciona. Si lo subís, te dejo el archivo entero patcheado.
//
//  Mientras tanto, esto es EXACTAMENTE lo que hay que agregar y en
//  qué posición, para que coincida byte a byte con el nuevo
//  TrackingPacket del driver (HandData ahora con curl[5] al final).
// ═════════════════════════════════════════════════════════════════
//
//  Layout que espera el driver por cada HandData (20 floats, en ESTE
//  orden exacto — antes eran 15, se agregan 5 al final):
//
//    x, y, z, qx, qy, qz, qw, trigger, grip, joyX, joyY,
//    sysBtn, appBtn, clickBtn, isTracked,
//    curlThumb, curlIndex, curlMiddle, curlRing, curlPinky   <── NUEVO
//
//  Paquete completo (TrackingPacket), en orden:
//    token (1 float) + left (20 floats) + right (20 floats) + hmd (8 floats)
//    = 49 floats = 196 bytes  (antes: 39 floats = 156 bytes)
//
// ─────────────────────────────────────────────────────────────────
//  Si tu código arma el paquete con algo tipo ByteBuffer, buscá la
//  función que escribe un HandData completo (probablemente algo como
//  writeHand(buffer, pose) o similar) y AGREGALE, justo después de
//  escribir isTracked, estas 5 líneas:
// ─────────────────────────────────────────────────────────────────
//
//  fun writeHand(buffer: ByteBuffer, pose: HandPose) {
//      buffer.putFloat(pose.x)
//      buffer.putFloat(pose.y)
//      buffer.putFloat(pose.z)
//      buffer.putFloat(pose.qx)
//      buffer.putFloat(pose.qy)
//      buffer.putFloat(pose.qz)
//      buffer.putFloat(pose.qw)
//      buffer.putFloat(/* trigger, lo que ya tengas */)
//      buffer.putFloat(pose.grip)
//      buffer.putFloat(/* joyX */)
//      buffer.putFloat(/* joyY */)
//      buffer.putFloat(/* sysBtn */)
//      buffer.putFloat(/* appBtn */)
//      buffer.putFloat(/* clickBtn */)
//      buffer.putFloat(if (pose.tracked) 1f else 0f)
//
//      // ── NUEVO: agregar exactamente estas 5 líneas acá ──
//      buffer.putFloat(pose.curlThumb)
//      buffer.putFloat(pose.curlIndex)
//      buffer.putFloat(pose.curlMiddle)
//      buffer.putFloat(pose.curlRing)
//      buffer.putFloat(pose.curlPinky)
//  }
//
// ─────────────────────────────────────────────────────────────────
//  Si en cambio tu paquete se arma con un ByteArray + offsets a mano
//  (menos común pero posible), el patrón es el mismo: los 5 floats
//  de curl van pegados justo después del float de isTracked de esa
//  mano, ANTES de empezar con los campos de la otra mano.
//
//  Y si el buffer completo se pre-alloca con un tamaño fijo (ej.
//  ByteBuffer.allocate(PACKET_BYTES) o similar en Kotlin), ese
//  tamaño también hay que subirlo de 156 a 196 bytes (39*4 -> 49*4).
// ─────────────────────────────────────────────────────────────────
//
//  MANDAME OscSender.kt (o como se llame el archivo real) y te dejo
//  el archivo entero ya patcheado y probado contra este layout.