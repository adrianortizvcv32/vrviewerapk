package com.example.vrviewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Navegador embebido para la ventana del Menú Hub. Vive fuera de pantalla
 * (adjunto al árbol de vistas para que Chromium componga, pero desplazado
 * lejos del viewport) y se vuelca a un Bitmap ~10 veces por segundo, que
 * alimenta glRenderer.updateWindowBitmap().
 *
 * Interacción con el dedo índice (ver HubPointerController):
 *  - touchDown/touchMove/touchUp reenvían eventos de touch REALES al
 *    WebView, igual que en una pantalla táctil. Es el propio WebView el
 *    que decide si es un tap (poco movimiento) o un scroll/drag (más
 *    movimiento) — no reinventamos esa lógica aquí.
 *  - Se inyecta un teclado HTML/JS dentro de la propia página cuando un
 *    <input>/<textarea> recibe foco. Es necesario porque el teclado del
 *    sistema Android se dibuja en una ventana aparte, fuera de
 *    WebView.draw(canvas), así que nunca aparecería en la captura que
 *    alimenta la textura GL del visor VR. Un teclado dentro del DOM sí
 *    se captura junto con el resto de la página.
 *
 * NOTA sobre threading (touchDown/touchMove/touchUp): estas funciones
 * terminan llamando a wv.dispatchTouchEvent(), una operación que Android
 * exige ejecutar en el hilo de UI. Este WebView tiene DOS fuentes de
 * touch: (1) el touch físico de pantalla, que ya llega desde el hilo de
 * UI (dispatchTouchEvent de la Activity), y (2) el "apuntado" con el
 * dedo índice vía HubPointerController, que se actualiza desde el hilo
 * de procesamiento de manos de SixDofTracker — un hilo de fondo. Si (2)
 * llega a llamar aquí sin pasar por el hilo de UI, el dispatch al
 * WebView desde un hilo no-UI puede corromper el estado interno del
 * gesture recognizer de Chromium (causa observada: el glow/rebote de
 * overscroll queda "pegado" o se comporta erráticamente). Para blindar
 * la clase contra ese error de uso (en vez de confiar en que el
 * llamador siempre postee al hilo de UI), cada método público verifica
 * el hilo actual y, si no es el de UI, postea el trabajo real con el
 * mismo `handler` que ya usa esta clase para el timer de captura.
 *
 * NOTA sobre el clamp de overscroll en el tope (pantalla se tapa con un
 * color sólido al arrastrar hacia abajo, se destapa al arrastrar hacia
 * arriba): confirmado que pasa específicamente al llegar arriba del todo
 * del scroll y seguir arrastrando hacia abajo. Se probaron dos fixes
 * declarativos que NO alcanzaron:
 *   1) wv.overScrollMode = OVER_SCROLL_NEVER — solo apaga el efecto
 *      nativo de la View de Android, no el glow/rebote interno que
 *      Chromium implementa en su propio compositor para WebView (un bug
 *      conocido: ese ajuste no siempre lo suprime).
 *   2) CSS `overscroll-behavior: none` + listener JS con preventDefault
 *      en touchmove — tampoco lo evitó, lo que sugiere que el efecto no
 *      pasa por el pipeline de eventos JS de la página, sino que vive
 *      directamente en el compositor nativo de Chromium.
 * En vez de intentar SUPRIMIR el efecto después de iniciado, lo evitamos
 * en el origen: como los touches que llegan al WebView son SINTÉTICOS
 * (los generamos nosotros en touchDown/touchMove/touchUp), podemos
 * frenarlos ANTES de reenviarlos. wv.scrollY es una propiedad nativa de
 * View — se lee de forma síncrona, sin evaluateJavascript — así que en
 * cada touchMove comprobamos si el WebView ya está en scrollY 0 y el
 * dedo sigue moviéndose hacia abajo; si es así, NO reenviamos ese
 * movimiento (lo retenemos en un "ancla"), y el compositor de Chromium
 * nunca llega a ver el gesto que dispara su glow/rebote interno.
 *
 * NOTA sobre LAYER_TYPE_SOFTWARE: Chromium normalmente hace scroll usando
 * un compositor de HARDWARE independiente del hilo de UI, para que sea
 * fluido. Ese compositor no siempre está sincronizado con canvas.draw()
 * (que es lo que usamos para capturar el frame), así que durante un
 * scroll activo la captura puede salir en gris/blanco. Forzar la capa a
 * software evita ese desfase: todo el dibujo pasa por el mismo pipeline
 * que capturamos, a costa de algo de rendimiento de scroll nativo (que
 * aquí no importa, porque de todas formas el usuario nunca ve el WebView
 * directamente — solo el Bitmap capturado).
 *
 * NOTA sobre offscreenPreRaster (causa RAÍZ del gris al deslizar):
 * Chromium decide cuánto margen de tiles rasterizar por adelantado según
 * si la vista está REALMENTE on-screen (posición real en la window), no
 * según sus layout bounds. Como este WebView vive permanentemente en
 * translationX = -10000f (fuera del área visible real), Android/Chromium
 * lo trata como "no visible" y solo rasteriza el viewport exacto, sin
 * colchón extra. Al scrollear rápido, el tile recién revelado todavía no
 * fue pintado -> gris. offscreenPreRaster=true le dice explícitamente a
 * Chromium "seguí rasterizando con margen aunque parezca offscreen",
 * que es justo el escenario de este WebView.
 *
 * NOTA sobre el fling (ver touchUp): incluso con LAYER_TYPE_SOFTWARE y
 * offscreenPreRaster, WebView calcula la velocidad del gesto en el
 * ACTION_UP y, si es alta, lanza SU PROPIA animación de scroll con
 * inercia en su compositor interno — que vuelve a correr de forma
 * asíncrona a nuestras capturas. Por eso el gris podía aparecer sobre
 * todo justo al soltar el dedo dentro de listas largas (como los
 * resultados de búsqueda de Google). Anulamos la velocidad mandando un
 * ACTION_MOVE extra en el mismo punto justo antes del ACTION_UP.
 *
 * NOTA sobre el buffering y el TEARING (texto rasgado/duplicado, foto
 * confirmada): la versión anterior usaba 2 buffers (bufA/bufB) alternados
 * SIEMPRE, sin ninguna confirmación de que el hilo GL ya había terminado
 * de leer un buffer antes de que el hilo de UI volviera a escribir sobre
 * él. Con solo 2 buffers y captura a hasta ~60/s (por el throttle de
 * captureFrameThrottled), si el hilo GL se atrasaba un instante (carga
 * de ARCore + MediaPipe + cámara + este WebView compitiendo), el hilo de
 * UI podía empezar a pintar wv.draw(canvas) sobre un buffer que GL
 * todavía estaba subiendo con texImage2D — resultado: una textura con
 * mitad de un frame y mitad de otro (la costura horizontal con texto
 * duplicado que se vio en la captura).
 *
 * Fix: cada buffer ahora tiene una bandera "busy". captureFrame() NUNCA
 * escribe sobre un buffer todavía busy — si los 3 buffers están
 * ocupados, directamente se saltea esa captura (se reintenta en el
 * próximo tick). onFrame() ahora entrega, junto con el Bitmap, un
 * callback `release` que el CONSUMIDOR (StereoGLRenderer) debe invocar
 * recién cuando termine texImage2D — es decir, cuando de verdad ya
 * copió los píxeles a la GPU y es seguro volver a pintar sobre ese
 * buffer. Se pasó de 2 a 3 buffers para dar más margen de maniobra
 * mientras alguno está en tránsito.
 *
 * NOTA sobre micro-stepping + captura event-driven (2da causa del gris
 * al deslizar): offscreenPreRaster le da a Chromium un margen extra de
 * tiles prerasterizados, pero ese margen es finito. Si un solo
 * ACTION_MOVE mueve el scroll un salto grande (típico cuando el touch
 * viene de tracking de mano a ~15-30fps, o de un timer de captura de
 * 100ms desacoplado del gesto real), el salto puede superar ese margen
 * igual, y aparece gris el trozo recién revelado. Dos cosas atacan esto:
 *  1) touchMove ya NO manda el salto entero en un solo evento: lo parte
 *     en pasos chicos (MAX_STEP_PX) y despacha varios ACTION_MOVE
 *     intermedios, para que Chromium nunca tenga que rasterizar de golpe
 *     más de lo que su margen de prefetch cubre.
 *  2) Cada touchDown/touchMove/touchUp dispara además una captura
 *     inmediata (throtteada a MIN_CAPTURE_GAP_MS) en vez de esperar al
 *     próximo tick del timer de 100ms — así el bitmap que sale hacia la
 *     textura GL está lo más cerca posible en el tiempo al estado real
 *     ya rasterizado, en vez de a un frame viejo o a medio camino.
 */
@SuppressLint("SetJavaScriptEnabled")
class HubBrowserView(
    private val context: Context,
    private val hostLayout: ViewGroup,
    private val widthPx: Int = 2560,
    private val heightPx: Int = 1440,
    // CAMBIO (fix tearing): onFrame ahora recibe también un callback
    // `release` que el consumidor DEBE invocar apenas termine de subir
    // el bitmap a la GPU (texImage2D). Hasta que no se llame, este
    // buffer queda "ocupado" y no se vuelve a escribir sobre él.
    private val onFrame: (Bitmap, release: () -> Unit) -> Unit
) {
    private var webView: WebView? = null

    // ── Triple buffer con locking real (ver nota de clase sobre tearing) ──
    private val BUF_COUNT = 3
    private val buffers = arrayOfNulls<Bitmap>(BUF_COUNT)
    private val bufferBusy = BooleanArray(BUF_COUNT)
    private var nextBufIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    @Volatile var grabbedIndicator = false

    private var lastTouchDownTime = 0L

    // ── Micro-stepping de scroll (ver nota de clase) ──
    private var lastDispatchedX = 0f
    private var lastDispatchedY = 0f

    // ── Clamp de overscroll en el tope (ver nota de clase) ──
    // Se activa cuando wv.scrollY llega a 0 y el dedo sigue arrastrando
    // hacia abajo (lo que intentaría seguir "tirando" del contenido hacia
    // arriba más allá del tope). En vez de reenviar ese movimiento al
    // WebView, lo retenemos: así el compositor de Chromium NUNCA llega a
    // ver el gesto que dispara su glow/rebote interno.
    private var clampedAtTop = false
    private var clampAnchorY = 0f

    // ── Captura event-driven, throtteada (ver nota de clase) ──
    private var lastCaptureTime = 0L

    companion object {
        private const val CAPTURE_INTERVAL_MS = 100L
        private const val MIN_CAPTURE_GAP_MS = 16L   // ~60fps tope para la captura "on-demand"
        private const val MAX_STEP_PX = 24f          // salto máximo por ACTION_MOVE despachado

        // Teclado virtual inyectado en el DOM de la página. Se reinyecta en
        // cada onPageFinished; el guard __vrKeyboardInstalled evita
        // duplicados dentro del mismo documento (y cada navegación es un
        // documento nuevo, así que no hay fugas entre páginas).
        private const val VR_KEYBOARD_JS = """
        (function() {
          if (window.__vrKeyboardInstalled) return;
          window.__vrKeyboardInstalled = true;

          var css = `
            #__vrkb { position:fixed; left:0; right:0; bottom:0; z-index:2147483647;
              background:#1e1e24; padding:6px; display:none;
              font-family:sans-serif; box-shadow:0 -2px 8px rgba(0,0,0,.4); }
            #__vrkb .row { display:flex; justify-content:center; margin:3px 0; }
            #__vrkb button { flex:1; margin:2px; padding:10px 0; font-size:16px;
              border:none; border-radius:6px; background:#3a3a44; color:#fff; }
            #__vrkb button.wide { flex:3; }
            #__vrkb button.hide { flex:1; background:#6366f1; }
          `;
          var style = document.createElement('style');
          style.textContent = css;
          document.head.appendChild(style);

          var kb = document.createElement('div');
          kb.id = '__vrkb';
          var rows = ['1234567890', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm'];
          rows.forEach(function(r) {
            var row = document.createElement('div');
            row.className = 'row';
            r.split('').forEach(function(ch) {
              var b = document.createElement('button');
              b.textContent = ch;
              row.appendChild(b);
            });
            kb.appendChild(row);
          });
          var lastRow = document.createElement('div');
          lastRow.className = 'row';
          var back = document.createElement('button'); back.textContent = '⌫';
          var space = document.createElement('button'); space.textContent = 'espacio'; space.className = 'wide';
          var enter = document.createElement('button'); enter.textContent = '↵';
          var hide = document.createElement('button'); hide.textContent = '▼'; hide.className = 'hide';
          lastRow.appendChild(back); lastRow.appendChild(space); lastRow.appendChild(enter); lastRow.appendChild(hide);
          kb.appendChild(lastRow);
          document.body.appendChild(kb);

          var target = null;

          function insert(text) {
            if (!target) return;
            var start = target.selectionStart != null ? target.selectionStart : target.value.length;
            var end = target.selectionEnd != null ? target.selectionEnd : target.value.length;
            var val = target.value || '';
            target.value = val.slice(0, start) + text + val.slice(end);
            var pos = start + text.length;
            try { target.setSelectionRange(pos, pos); } catch(e) {}
            target.dispatchEvent(new Event('input', { bubbles: true }));
          }

          function backspace() {
            if (!target) return;
            var start = target.selectionStart != null ? target.selectionStart : target.value.length;
            var end = target.selectionEnd != null ? target.selectionEnd : target.value.length;
            var val = target.value || '';
            if (start === end && start > 0) { start -= 1; }
            target.value = val.slice(0, start) + val.slice(end);
            try { target.setSelectionRange(start, start); } catch(e) {}
            target.dispatchEvent(new Event('input', { bubbles: true }));
          }

          kb.querySelectorAll('button').forEach(function(b) {
            // mousedown + preventDefault: evita robarle el foco al input
            b.addEventListener('mousedown', function(e) {
              e.preventDefault();
              if (b === back) backspace();
              else if (b === space) insert(' ');
              else if (b === enter) {
                var form = target && target.form;
                if (form) { (form.requestSubmit ? form.requestSubmit() : form.submit()); }
                else if (target) target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
              } else if (b === hide) {
                kb.style.display = 'none';
                if (target) target.blur();
              } else {
                insert(b.textContent);
              }
              if (target) target.focus();
            });
          });

          document.addEventListener('focusin', function(e) {
            var t = e.target;
            var editable = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
            if (editable) { target = t; kb.style.display = 'block'; }
          });
          document.addEventListener('focusout', function(e) {
            setTimeout(function() {
              if (document.activeElement === document.body || !document.activeElement) {
                kb.style.display = 'none';
              }
            }, 50);
          });
        })();
        """

        // FIX "se tapa todo al deslizar hacia abajo": confirmado que pasa
        // específicamente al llegar arriba del todo del scroll y seguir
        // arrastrando hacia abajo (overscroll/"pull to refresh" — Google
        // dispara ahí una animación a pantalla completa de color sólido).
        // El CSS `overscroll-behavior: none` (declarativo) NO alcanzó por
        // sí solo: probablemente porque acá el gesto entra como touch
        // SINTÉTICO vía dispatchTouchEvent (no un touch real de hardware),
        // y el glow/bounce nativo de Chromium para WebView no siempre
        // respeta ese CSS de forma confiable con ese tipo de entrada.
        // Reforzamos con un listener de "touchmove" en captura
        // (passive:false) que llama preventDefault() ANTES de que el
        // navegador llegue a iniciar el efecto, pero SOLO cuando:
        //   1) el scroll ya está en el tope (scrollY <= 0), Y
        //   2) el dedo se sigue moviendo hacia abajo respecto al touchstart
        // Fuera de esas condiciones no interferimos, así el scroll normal
        // (en cualquier otro punto de la página) sigue funcionando igual.
        // Se reinyecta en cada onPageFinished (cada navegación es un
        // documento nuevo, y __vrOverscrollGuardInstalled evita duplicar
        // el listener dentro del mismo documento).
        private const val VR_DISABLE_OVERSCROLL_JS = """
        (function() {
          if (window.__vrOverscrollGuardInstalled) return;
          window.__vrOverscrollGuardInstalled = true;

          var css = `
            html, body {
              overscroll-behavior: none !important;
              overscroll-behavior-y: none !important;
            }
          `;
          var style = document.createElement('style');
          style.textContent = css;
          document.head.appendChild(style);
          try {
            document.documentElement.style.overscrollBehavior = 'none';
            document.body.style.overscrollBehavior = 'none';
          } catch (e) {}

          function scrollTop() {
            return window.scrollY ||
                   document.documentElement.scrollTop ||
                   document.body.scrollTop || 0;
          }

          var startY = 0;
          var guarding = false;

          document.addEventListener('touchstart', function(e) {
            if (e.touches && e.touches.length > 0) {
              startY = e.touches[0].clientY;
            }
            guarding = false;
          }, { capture: true, passive: true });

          document.addEventListener('touchmove', function(e) {
            if (!e.touches || e.touches.length === 0) return;
            var curY = e.touches[0].clientY;
            var draggingDown = curY > startY;   // dedo bajando en pantalla

            if (guarding || (scrollTop() <= 0 && draggingDown)) {
              guarding = true;
              e.preventDefault();
            }
          }, { capture: true, passive: false });

          document.addEventListener('touchend', function() {
            guarding = false;
          }, { capture: true, passive: true });
        })();
        """
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureFrame()
            if (running) handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    /** true si ya estamos en el hilo principal (de UI). */
    private fun isOnMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Ejecuta [block] en el hilo de UI: directo si ya estamos ahí, o
     *  posteado con `handler` si nos llamaron desde otro hilo (p.ej. el
     *  hilo de procesamiento de manos de SixDofTracker). Ver nota de
     *  threading en el encabezado de la clase. */
    private fun runOnMainThread(block: () -> Unit) {
        if (isOnMainThread()) block() else handler.post(block)
    }

    fun start(initialUrl: String = "https://www.google.com") {
        if (webView != null) { load(initialUrl); return }

        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.setSupportZoom(false)
        // FIX gris al scrollear: este WebView vive attached-pero-offscreen
        // (translationX = -10000f). Chromium por defecto solo rasteriza el
        // viewport exacto en ese caso; este flag le pide margen extra.
        wv.settings.offscreenPreRaster = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(VR_DISABLE_OVERSCROLL_JS, null)
                view.evaluateJavascript(VR_KEYBOARD_JS, null)
            }
        }

        // Debe estar adjunto al árbol de vistas para que se componga, pero
        // no visible en pantalla: lo movemos fuera del viewport en vez de
        // usar GONE (GONE detiene el layout/composición interna).
        wv.translationX = -10000f
        wv.visibility = View.VISIBLE
        hostLayout.addView(wv)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, widthPx, heightPx)

        webView = wv
        for (i in 0 until BUF_COUNT) {
            buffers[i] = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bufferBusy[i] = false
        }
        nextBufIndex = 0
        lastDispatchedX = 0f
        lastDispatchedY = 0f
        lastCaptureTime = 0L
        clampedAtTop = false
        wv.loadUrl(initialUrl)

        running = true
        handler.post(captureRunnable)
    }

    fun load(url: String) { webView?.loadUrl(url) }
    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() { webView?.let { if (it.canGoForward()) it.goForward() } }

    /** Tap normalizado (0..1, 0..1) dentro de la ventana -> tap real en la página. */
    fun tapAt(uNorm: Float, vNorm: Float) {
        runOnMainThread { tapAtInternal(uNorm, vNorm) }
    }

    private fun tapAtInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up   = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(down)
        wv.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    /**
     * Inicio de un toque real (el dedo índice apenas entra a la ventana
     * apuntando). Reenviado por HubPointerController.
     *
     * Seguro de llamar desde cualquier hilo: si no estamos en el hilo de
     * UI, el trabajo real se postea con `handler` (ver runOnMainThread).
     */
    fun touchDown(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchDownInternal(uNorm, vNorm) }
    }

    private fun touchDownInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        wv.requestFocus()
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        lastTouchDownTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(lastTouchDownTime, lastTouchDownTime, MotionEvent.ACTION_DOWN, x, y, 0)
        wv.dispatchTouchEvent(ev)
        ev.recycle()
        lastDispatchedX = x
        lastDispatchedY = y
        clampedAtTop = false
        captureFrameThrottled()
    }

    /**
     * Arrastre continuo mientras el dedo sigue "tocando" la ventana.
     * Es lo que le permite al WebView reconocer un scroll/drag en vez
     * de un tap — igual que en pantalla táctil.
     *
     * Si el salto respecto al último punto despachado es grande (dedo/mano
     * moviéndose rápido, o baja frecuencia de muestreo upstream), lo parte
     * en varios ACTION_MOVE intermedios de a lo sumo MAX_STEP_PX, para que
     * Chromium nunca tenga que rasterizar de golpe más área de la que su
     * margen de prefetch (offscreenPreRaster) cubre.
     *
     * Seguro de llamar desde cualquier hilo (ver runOnMainThread).
     */
    fun touchMove(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchMoveInternal(uNorm, vNorm) }
    }

    private fun touchMoveInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val rawX = uNorm.coerceIn(0f, 1f) * widthPx
        val rawY = vNorm.coerceIn(0f, 1f) * heightPx

        // ── Clamp de overscroll en el tope (ver nota de clase) ──
        // Si el WebView ya está en scrollY 0 (arriba del todo) y el dedo
        // sigue moviéndose hacia abajo (rawY > lastDispatchedY), NO
        // reenviamos ese movimiento: lo retenemos en clampAnchorY. Así el
        // compositor de Chromium nunca ve el gesto que dispara su
        // glow/rebote interno. En cuanto el dedo revierte (empieza a
        // moverse hacia arriba de nuevo), soltamos el clamp y retomamos
        // el reenvío normal desde ese punto.
        val atTop = wv.scrollY <= 0
        if (atTop) {
            if (!clampedAtTop) {
                clampedAtTop = true
                clampAnchorY = lastDispatchedY
            }
            if (rawY >= clampAnchorY) {
                // Sigue "tirando" hacia abajo más allá del tope: no reenviar.
                lastDispatchedX = rawX
                captureFrameThrottled()
                return
            } else {
                // El dedo revirtió (ahora sube): soltar el clamp y seguir normal.
                clampedAtTop = false
            }
        } else {
            clampedAtTop = false
        }

        val x = rawX
        val y = rawY
        val dx = x - lastDispatchedX
        val dy = y - lastDispatchedY
        val dist = kotlin.math.hypot(dx, dy)
        val steps = (dist / MAX_STEP_PX).toInt().coerceAtLeast(1)

        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val stepX = lastDispatchedX + dx * t
            val stepY = lastDispatchedY + dy * t
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_MOVE, stepX, stepY, 0)
            wv.dispatchTouchEvent(ev)
            ev.recycle()
        }

        lastDispatchedX = x
        lastDispatchedY = y
        captureFrameThrottled()
    }

    /**
     * Fin del toque (el dedo deja de apuntar o sale de la ventana).
     *
     * Antes del ACTION_UP mandamos un ACTION_MOVE extra EN EL MISMO PUNTO
     * (con el reloj ligeramente adelantado). Esto hace que la velocidad
     * calculada por WebView entre los dos últimos puntos sea ≈0, así que
     * WebView NO dispara su propio fling (scroll con inercia). Esto sigue
     * sumando además del fix de offscreenPreRaster: uno ataca la causa
     * de fondo (rasterizado con margen), el otro evita que quede
     * corriendo una animación de inercia asíncrona a nuestras capturas.
     *
     * Seguro de llamar desde cualquier hilo (ver runOnMainThread).
     */
    fun touchUp(uNorm: Float, vNorm: Float) {
        runOnMainThread { touchUpInternal(uNorm, vNorm) }
    }

    private fun touchUpInternal(uNorm: Float, vNorm: Float) {
        val wv = webView ?: return
        val x = uNorm.coerceIn(0f, 1f) * widthPx
        val y = vNorm.coerceIn(0f, 1f) * heightPx
        val now = SystemClock.uptimeMillis() + 40L

        val settle = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_MOVE, x, y, 0)
        wv.dispatchTouchEvent(settle)
        settle.recycle()

        val ev = MotionEvent.obtain(lastTouchDownTime, now, MotionEvent.ACTION_UP, x, y, 0)
        wv.dispatchTouchEvent(ev)
        ev.recycle()
        captureFrameThrottled()
    }

    /** Fuerza una captura fuera del timer de 100ms, pero sin pasarse de
     *  MIN_CAPTURE_GAP_MS entre capturas (evita saturar el hilo de UI si
     *  llegan touchMove muy seguidos, p.ej. desde tracking de mano).
     *  Se llama siempre desde touchDown/Move/UpInternal, que ya corren
     *  en el hilo de UI gracias a runOnMainThread. */
    private fun captureFrameThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureTime < MIN_CAPTURE_GAP_MS) return
        lastCaptureTime = now
        captureFrame()
    }

    private fun captureFrame() {
        val wv = webView ?: return

        // Buscar el próximo buffer libre (no busy) empezando por
        // nextBufIndex. Si los BUF_COUNT están ocupados (GL muy
        // atrasado), nos salteamos esta captura entera en vez de
        // arriesgar tearing — se reintenta en el próximo tick del timer
        // o del throttle (ver nota de clase sobre el fix de tearing).
        var idx = nextBufIndex
        var scanned = 0
        while (bufferBusy[idx] && scanned < BUF_COUNT) {
            idx = (idx + 1) % BUF_COUNT
            scanned++
        }
        if (bufferBusy[idx]) return  // los BUF_COUNT buffers siguen ocupados: saltar frame

        val target = buffers[idx] ?: return
        try {
            val canvas = Canvas(target)
            wv.draw(canvas)
            if (grabbedIndicator) {
                val paint = Paint().apply {
                    color = 0xFF6366F1.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                }
                canvas.drawRect(5f, 5f, widthPx - 5f, heightPx - 5f, paint)
            }

            bufferBusy[idx] = true
            nextBufIndex = (idx + 1) % BUF_COUNT

            onFrame(target) {
                // Se llama desde el hilo GL apenas texImage2D termina de
                // leer los píxeles: recién ahí es seguro volver a pintar
                // sobre este buffer.
                bufferBusy[idx] = false
            }
        } catch (_: Exception) {
            bufferBusy[idx] = false
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(captureRunnable)
        webView?.let { hostLayout.removeView(it); it.destroy() }
        webView = null
        for (i in 0 until BUF_COUNT) { buffers[i] = null; bufferBusy[i] = false }
    }
}