package com.wolf.iptv

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    // Tentativas automáticas quando a conexão cai ou dá timeout.
    private var tentativasRede = 0
    private val maxTentativasRede = 5

    // ---- AO VIVO ----
    private var ehAoVivo = false
    private var urlOriginal = ""
    private var urlFinal = ""
    private var jaTentouOriginal = false
    private var usarM3u8 = true

    private lateinit var container: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var erroTexto: TextView
    private lateinit var menuZoom: LinearLayout

    private var menuAberto = false
    private var zoomAtual = 0

    // Botão que alterna entre TS e M3U8 nos canais ao vivo.
    private lateinit var botaoFormato: TextView
    // true enquanto o usuário está em outra tela (espelhar / compartilhar):
    // evita pausar o vídeo quando o app sai de cena por um instante.
    private var abrindoEspelhamento = false

    // ---- LISTA DE EPISÓDIOS (atual + próximos) ----
    private var epUrls: MutableList<String> = mutableListOf()
    private var epTitulos: MutableList<String> = mutableListOf()
    private var epChaves: MutableList<String> = mutableListOf()
    private var posAtual = 0
    private var urlAtualVideo = ""
    private var tituloAtualVideo = ""
    private var metaVideo = ""
    private var fimDoEpisodio = false
    private lateinit var botaoProximo: TextView
    // Quando o episódio acaba, passa sozinho pro próximo depois disso. 0 = desliga.
    private val segundosAutoProximo = 10
    private var contagemProximo = 0
    private val contagemLoop = object : Runnable {
        override fun run() {
            if (contagemProximo <= 0) {
                tocarProximo()
                return
            }
            botaoProximo.text = "⏭  Próximo episódio ($contagemProximo)"
            contagemProximo--
            botaoProximo.postDelayed(this, 1000)
        }
    }

    private val zooms = floatArrayOf(1.0f, 1.1f, 1.25f, 1.5f, 2.0f)
    private val nomesZoom = arrayOf("1.0×   Normal", "1.1×", "1.25×", "1.5×", "2.0×")

    private val velocidades = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var idxVelocidade = 2

    private val vermelho = Color.rgb(229, 9, 20)

    // ---- CONTROLES (visual novo) ----
    private lateinit var controles: FrameLayout
    private lateinit var tituloView: TextView
    private lateinit var subtituloView: TextView
    private lateinit var btnCast: IconeView
    private lateinit var btnCC: IconeView
    private lateinit var btnEngrenagem: IconeView
    private lateinit var btnTela: IconeView
    private lateinit var icAnterior: IconeView
    private lateinit var icVoltar10: IconeView
    private lateinit var icPlay: IconeView
    private lateinit var icAvancar10: IconeView
    private lateinit var icProximo: IconeView
    private lateinit var seek: SeekBar
    private lateinit var textoTempo: TextView
    private lateinit var btnVelocidade: LinearLayout
    private lateinit var textoVelocidade: TextView
    private lateinit var btnEpisodios: LinearLayout
    private lateinit var btnLista: LinearLayout
    private lateinit var btnCompartilhar: LinearLayout
    private lateinit var btnAudio: LinearLayout

    private var controlesVisiveis = true
    private var arrastando = false

    private val prefs by lazy { getSharedPreferences("wolf_prefs", MODE_PRIVATE) }

    // Salva a posição do vídeo a cada 5 segundos (continuar assistindo).
    private val salvarLoop = object : Runnable {
        override fun run() {
            salvarProgresso()
            playerView.postDelayed(this, 5000)
        }
    }

    private val esconderRunnable = Runnable { esconderControles() }

    private val progressoLoop = object : Runnable {
        override fun run() {
            atualizarProgresso()
            if (controlesVisiveis) playerView.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        aplicarImersivo()

        usarM3u8 = prefs.getBoolean("ao_vivo_m3u8", true)

        criarInterface()
        iniciarPlayer()
    }

    private fun aplicarImersivo() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) aplicarImersivo()
    }

    // =========================================================
    // ÍCONES (desenhados em código, sem precisar de arquivos)
    // =========================================================

    private inner class IconeView(
        ctx: Context,
        var tipo: String,
        private val fracao: Float
    ) : View(ctx) {
        private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.9f
        }
        private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
        }
        private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private fun linhas(c: Canvas, vararg pts: Float) {
            val p = Path()
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                p.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            c.drawPath(p, traco)
        }

        private fun triangulo(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val p = Path()
            p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.close()
            c.drawPath(p, cheio)
        }

        override fun onDraw(c: Canvas) {
            val lado = minOf(width, height) * fracao
            if (lado <= 0f) return
            val u = lado / 24f
            c.save()
            c.translate((width - lado) / 2f, (height - lado) / 2f)
            c.scale(u, u)

            when (tipo) {
                "voltar" -> {
                    linhas(c, 20f, 12f, 5f, 12f)
                    linhas(c, 11f, 6f, 5f, 12f, 11f, 18f)
                }
                "cast" -> {
                    linhas(c, 3f, 9f, 3f, 5f, 21f, 5f, 21f, 19f, 14f, 19f)
                    c.drawArc(RectF(-1f, 15f, 7f, 23f), 270f, 90f, false, traco)
                    c.drawArc(RectF(-5f, 11f, 11f, 27f), 270f, 90f, false, traco)
                    c.drawCircle(3f, 19f, 1.3f, cheio)
                }
                "cc" -> {
                    c.drawRoundRect(RectF(2f, 5f, 22f, 19f), 3f, 3f, traco)
                    texto.textSize = 8.5f
                    c.drawText("CC", 12f, 15f, texto)
                }
                "engrenagem" -> {
                    c.drawCircle(12f, 12f, 3.2f, traco)
                    c.drawCircle(12f, 12f, 7f, traco)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val cs = Math.cos(a).toFloat()
                        val sn = Math.sin(a).toFloat()
                        c.drawLine(12f + cs * 7f, 12f + sn * 7f, 12f + cs * 10f, 12f + sn * 10f, traco)
                    }
                }
                "tela" -> {
                    linhas(c, 9f, 3f, 9f, 9f, 3f, 9f)
                    linhas(c, 15f, 3f, 15f, 9f, 21f, 9f)
                    linhas(c, 9f, 21f, 9f, 15f, 3f, 15f)
                    linhas(c, 15f, 21f, 15f, 15f, 21f, 15f)
                }
                "anterior" -> {
                    c.drawRect(5.5f, 6f, 7.5f, 18f, cheio)
                    triangulo(c, 18.5f, 6f, 18.5f, 18f, 9f, 12f)
                }
                "proximo" -> {
                    c.drawRect(16.5f, 6f, 18.5f, 18f, cheio)
                    triangulo(c, 5.5f, 6f, 5.5f, 18f, 15f, 12f)
                }
                "voltar10", "avancar10" -> {
                    c.save()
                    if (tipo == "voltar10") c.scale(-1f, 1f, 12f, 12f)
                    c.drawArc(RectF(4f, 5f, 20f, 21f), 270f, -300f, false, traco)
                    linhas(c, 10.5f, 2.2f, 13.5f, 5f, 10.5f, 7.8f)
                    c.restore()
                    texto.textSize = 7.2f
                    c.drawText("10", 12f, 15.6f, texto)
                }
                "pause" -> {
                    c.drawRoundRect(RectF(6.5f, 4.5f, 10.5f, 19.5f), 1.2f, 1.2f, cheio)
                    c.drawRoundRect(RectF(13.5f, 4.5f, 17.5f, 19.5f), 1.2f, 1.2f, cheio)
                }
                "play" -> {
                    triangulo(c, 8f, 4.5f, 8f, 19.5f, 19.5f, 12f)
                }
                "episodios" -> {
                    linhas(c, 8f, 3f, 16f, 3f)
                    linhas(c, 6f, 6f, 18f, 6f)
                    c.drawRoundRect(RectF(3f, 9f, 21f, 21f), 2.5f, 2.5f, traco)
                    triangulo(c, 10f, 12.5f, 10f, 17.5f, 14.5f, 15f)
                }
                "marcador", "marcador_cheio" -> {
                    val p = Path()
                    p.moveTo(6f, 3f); p.lineTo(18f, 3f); p.lineTo(18f, 21f)
                    p.lineTo(12f, 16.5f); p.lineTo(6f, 21f); p.close()
                    c.drawPath(p, if (tipo == "marcador_cheio") cheio else traco)
                }
                "compartilhar" -> {
                    val p = Path()
                    p.moveTo(4f, 19f)
                    p.cubicTo(4f, 12f, 8f, 9f, 16f, 9f)
                    c.drawPath(p, traco)
                    linhas(c, 15f, 4f, 21f, 9f, 15f, 14f)
                }
                "audio" -> {
                    val p = Path()
                    p.moveTo(3f, 9f); p.lineTo(7f, 9f); p.lineTo(12f, 5f)
                    p.lineTo(12f, 19f); p.lineTo(7f, 15f); p.lineTo(3f, 15f); p.close()
                    c.drawPath(p, traco)
                    c.drawArc(RectF(8f, 8f, 16f, 16f), -45f, 90f, false, traco)
                    c.drawArc(RectF(4f, 4f, 20f, 20f), -45f, 90f, false, traco)
                }
                "velocidade" -> {
                    c.drawArc(RectF(3f, 4f, 21f, 22f), 150f, 240f, false, traco)
                    c.drawLine(12f, 13f, 16.5f, 8.5f, traco)
                    c.drawCircle(12f, 13f, 1.4f, cheio)
                }
            }
            c.restore()
        }
    }

    // =========================================================
    // INTERFACE
    // =========================================================

    private fun ajustarFoco(v: View, base: GradientDrawable?, oval: Boolean) {
        v.background = base
        v.setOnFocusChangeListener { view, foco ->
            view.background = if (foco) {
                GradientDrawable().apply {
                    if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(24).toFloat()
                    setColor(Color.argb(90, 255, 255, 255))
                    setStroke(dp(2), Color.WHITE)
                }
            } else {
                base
            }
        }
    }

    private fun fundoCirculo(alfa: Int, anel: Int, espessuraDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alfa, 0, 0, 0))
            setStroke(dp(espessuraDp), anel)
        }

    private fun fundoPilula(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(140, 18, 18, 18))
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }

    private fun botaoIcone(
        tipo: String,
        fracao: Float,
        fundo: GradientDrawable?,
        acao: () -> Unit
    ): IconeView {
        val v = IconeView(this, tipo, fracao)
        v.isFocusable = true
        v.isClickable = true
        ajustarFoco(v, fundo, true)
        v.setOnClickListener {
            agendarEsconder()
            acao()
        }
        return v
    }

    private fun criarPilula(tipoIcone: String, texto: String, comFundo: Boolean, acao: () -> Unit): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
        }
        l.addView(
            IconeView(this, tipoIcone, 1f),
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }
        )
        l.addView(TextView(this).apply {
            text = texto
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
        })
        ajustarFoco(l, if (comFundo) fundoPilula() else null, false)
        l.setOnClickListener {
            agendarEsconder()
            acao()
        }
        return l
    }

    private fun textoDaPilula(p: LinearLayout): TextView = p.getChildAt(1) as TextView
    private fun iconeDaPilula(p: LinearLayout): IconeView = p.getChildAt(0) as IconeView

    private fun criarInterface() {
        container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        playerView.setKeepContentOnPlayerReset(true)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        playerView.setOnClickListener { mostrarControles() }
        // Sem isso o foco do controle remoto fica preso no vídeo e não chega nos botões.
        playerView.isFocusable = false
        playerView.isFocusableInTouchMode = false

        container.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // ERRO
        erroTexto = TextView(this)
        erroTexto.textSize = 18f
        erroTexto.setTextColor(Color.WHITE)
        erroTexto.setBackgroundColor(Color.BLACK)
        erroTexto.gravity = Gravity.CENTER
        erroTexto.setPadding(40, 40, 40, 40)
        erroTexto.visibility = View.GONE
        container.addView(
            erroTexto,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // ───────── CONTROLES ─────────
        controles = FrameLayout(this)
        controles.isClickable = true
        controles.setOnClickListener { esconderControles() }
        controles.isFocusable = false
        controles.isFocusableInTouchMode = false

        // degradês de topo e base
        controles.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT)
                )
            },
            FrameLayout.LayoutParams(-1, dp(130), Gravity.TOP)
        )
        controles.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Color.argb(225, 0, 0, 0), Color.TRANSPARENT)
                )
            },
            FrameLayout.LayoutParams(-1, dp(210), Gravity.BOTTOM)
        )

        // ----- barra de cima -----
        val barraTopo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val btnVoltar = botaoIcone("voltar", 0.62f, null) { finish() }
        barraTopo.addView(btnVoltar, LinearLayout.LayoutParams(dp(44), dp(44)))

        val blocoTitulo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        tituloView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        subtituloView = TextView(this).apply {
            setTextColor(Color.rgb(200, 205, 215))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        blocoTitulo.addView(tituloView)
        blocoTitulo.addView(subtituloView)
        barraTopo.addView(blocoTitulo, LinearLayout.LayoutParams(0, -2, 1f))

        // Formato TS / M3U8 (só aparece em canal ao vivo)
        botaoFormato = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                agendarEsconder()
                alternarFormato()
            }
        }
        ajustarFoco(botaoFormato, fundoPilula(), false)
        barraTopo.addView(botaoFormato, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

        btnCast = botaoIcone("cast", 0.62f, null) { abrirEspelhamento() }
        btnCC = botaoIcone("cc", 0.62f, null) { abrirFaixas(true) }
        btnEngrenagem = botaoIcone("engrenagem", 0.62f, null) {
            if (menuAberto) fecharMenuZoom() else abrirMenuZoom()
        }
        btnTela = botaoIcone("tela", 0.62f, null) { alternarEscala() }
        listOf(btnCast, btnCC, btnEngrenagem, btnTela).forEach {
            barraTopo.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        }
        controles.addView(barraTopo, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ----- centro -----
        val anel = Color.argb(220, 190, 220, 240)
        icAnterior = botaoIcone("anterior", 0.5f, null) { tocarAnterior() }
        icVoltar10 = botaoIcone("voltar10", 0.62f, fundoCirculo(110, Color.argb(90, 255, 255, 255), 1)) {
            seekRelativo(-10_000L)
        }
        icPlay = botaoIcone("pause", 0.5f, fundoCirculo(120, anel, 2)) { alternarPlay() }
        icAvancar10 = botaoIcone("avancar10", 0.62f, fundoCirculo(110, Color.argb(90, 255, 255, 255), 1)) {
            seekRelativo(10_000L)
        }
        icProximo = botaoIcone("proximo", 0.5f, null) { tocarProximo() }

        val centro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun add(v: View, tam: Int) {
            centro.addView(v, LinearLayout.LayoutParams(dp(tam), dp(tam)).apply {
                leftMargin = dp(12); rightMargin = dp(12)
            })
        }
        add(icAnterior, 50)
        add(icVoltar10, 64)
        add(icPlay, 84)
        add(icAvancar10, 64)
        add(icProximo, 50)
        controles.addView(centro, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // ----- base -----
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(12))
        }

        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(vermelho)
            thumbTintList = ColorStateList.valueOf(vermelho)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(110, 255, 255, 255))
            secondaryProgressTintList = ColorStateList.valueOf(Color.argb(150, 255, 255, 255))
            keyProgressIncrement = 10_000
            max = 1
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progresso: Int, doUsuario: Boolean) {
                if (!doUsuario) return
                atualizarTextoTempo(progresso.toLong(), duracaoAtual())
                if (!arrastando) player?.seekTo(progresso.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                arrastando = true
                playerView.removeCallbacks(esconderRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                arrastando = false
                player?.seekTo((sb?.progress ?: 0).toLong())
                agendarEsconder()
            }
        })
        base.addView(seek, LinearLayout.LayoutParams(-1, -2))

        val linhaTempo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        textoTempo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        linhaTempo.addView(textoTempo, LinearLayout.LayoutParams(0, -2, 1f))
        btnVelocidade = criarPilula("velocidade", "1.0x", true) { alternarVelocidade() }
        textoVelocidade = textoDaPilula(btnVelocidade)
        textoVelocidade.setTypeface(null, Typeface.BOLD)
        linhaTempo.addView(btnVelocidade, LinearLayout.LayoutParams(-2, -2))
        base.addView(linhaTempo, LinearLayout.LayoutParams(-1, -2))

        val linhaBotoes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnEpisodios = criarPilula("episodios", "Episódios", false) { abrirEpisodios() }
        btnLista = criarPilula("marcador", "Minha Lista", false) { alternarLista() }
        btnCompartilhar = criarPilula("compartilhar", "Compartilhar", false) { compartilhar() }
        btnAudio = criarPilula("audio", "Áudio e Legendas", true) { abrirFaixas(false) }
        linhaBotoes.addView(btnEpisodios)
        linhaBotoes.addView(btnLista, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        linhaBotoes.addView(btnCompartilhar, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        linhaBotoes.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        linhaBotoes.addView(btnAudio)
        base.addView(linhaBotoes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        controles.addView(base, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // BOTÃO PRÓXIMO EPISÓDIO (contagem ao terminar)
        botaoProximo = TextView(this).apply {
            text = "⏭  Próximo episódio"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { tocarProximo() }
        }
        ajustarFoco(
            botaoProximo,
            GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(vermelho)
            },
            false
        )
        controles.addView(
            botaoProximo,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(24), dp(150))
            }
        )

        container.addView(controles, FrameLayout.LayoutParams(-1, -1))

        // MENU ZOOM (abre pela engrenagem)
        menuZoom = LinearLayout(this)
        menuZoom.orientation = LinearLayout.VERTICAL
        menuZoom.setPadding(20, 20, 20, 20)
        menuZoom.background = fundoArredondado(Color.rgb(10, 10, 10), 24f)
        menuZoom.elevation = 30f
        menuZoom.visibility = View.GONE
        menuZoom.isFocusable = true

        val menuParams = FrameLayout.LayoutParams(390, FrameLayout.LayoutParams.WRAP_CONTENT)
        menuParams.gravity = Gravity.TOP or Gravity.END
        menuParams.setMargins(0, dp(70), dp(24), 0)
        container.addView(menuZoom, menuParams)
        criarBotoesZoom()

        setContentView(container)

        // Ao abrir o filme, o foco já começa no botão de play.
        container.post { if (controlesVisiveis) icPlay.requestFocus() }

        tituloView.text = ""
        atualizarLista()
    }

    // =========================================================
    // MOSTRAR / ESCONDER CONTROLES
    // =========================================================

    private fun mostrarControles() {
        controlesVisiveis = true
        controles.visibility = View.VISIBLE
        controles.alpha = 1f
        atualizarProgresso()
        playerView.removeCallbacks(progressoLoop)
        playerView.postDelayed(progressoLoop, 500)
        agendarEsconder()
        if (!focoNosControles()) icPlay.requestFocus()
    }

    // true quando o foco do controle está em algum botão da tela de controles.
    private fun focoNosControles(): Boolean {
        var p: View? = currentFocus
        while (p != null) {
            if (p === controles) return true
            p = p.parent as? View
        }
        return false
    }

    private fun esconderControles() {
        if (menuAberto || contagemProximo > 0 || fimDoEpisodio) return
        if (arrastando) return
        controlesVisiveis = false
        controles.visibility = View.GONE
        playerView.removeCallbacks(esconderRunnable)
        playerView.removeCallbacks(progressoLoop)
    }

    private fun agendarEsconder() {
        playerView.removeCallbacks(esconderRunnable)
        if (player?.isPlaying == true) {
            playerView.postDelayed(esconderRunnable, 5000)
        }
    }

    private fun atualizarIconePlay() {
        icPlay.tipo = if (player?.isPlaying == true) "pause" else "play"
        icPlay.invalidate()
    }

    // =========================================================
    // PROGRESSO / TEMPO
    // =========================================================

    private fun formatarTempo(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun duracaoAtual(): Long {
        val d = player?.duration ?: 0L
        return if (d > 0L) d else 0L
    }

    private fun atualizarTextoTempo(pos: Long, dur: Long) {
        val a = formatarTempo(pos)
        val s = android.text.SpannableString(a + " / " + formatarTempo(dur))
        s.setSpan(
            android.text.style.ForegroundColorSpan(Color.rgb(170, 175, 185)),
            a.length, s.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        textoTempo.text = s
    }

    private fun atualizarProgresso() {
        val p = player ?: return
        if (ehAoVivo) {
            val s = android.text.SpannableString("●  AO VIVO")
            s.setSpan(
                android.text.style.ForegroundColorSpan(vermelho),
                0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            textoTempo.text = s
            return
        }
        val dur = duracaoAtual()
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (!arrastando) {
            seek.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            seek.progress = pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            seek.secondaryProgress = p.bufferedPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            atualizarTextoTempo(pos, dur)
        }
    }

    private fun seekRelativo(delta: Long) {
        if (ehAoVivo) return
        player?.let {
            val alvo = (it.currentPosition + delta).coerceAtLeast(0L)
            val dur = it.duration
            it.seekTo(if (dur > 0L) minOf(alvo, dur) else alvo)
        }
        atualizarProgresso()
    }

    private fun alternarPlay() {
        player?.let {
            when {
                it.playbackState == Player.STATE_IDLE -> {
                    // Depois de transmitir um canal ao vivo, o player ficou parado.
                    it.prepare()
                    it.play()
                }
                it.playbackState == Player.STATE_ENDED -> {
                    it.seekTo(0L)
                    it.play()
                }
                it.isPlaying -> it.pause()
                else -> it.play()
            }
        }
    }

    // =========================================================
    // TÍTULO / MODO (filme, série, ao vivo)
    // =========================================================

    private fun metaBonita(meta: String): String =
        meta.split(",", "/", ";", "|", "•")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("  •  ")

    private fun atualizarTitulo() {
        tituloView.text = tituloAtualVideo
        val m = metaBonita(metaVideo)
        subtituloView.text = m
        subtituloView.visibility = if (m.isBlank()) View.GONE else View.VISIBLE
    }

    private fun aplicarModo() {
        val vod = if (ehAoVivo) View.GONE else View.VISIBLE
        icVoltar10.visibility = vod
        icAvancar10.visibility = vod
        icAnterior.visibility = vod
        icProximo.visibility = vod
        seek.visibility = vod
        btnVelocidade.visibility = vod
        btnLista.visibility = vod
        btnEpisodios.visibility = if (!ehAoVivo && epUrls.size > 1) View.VISIBLE else View.GONE
        atualizarNavegacao()
    }

    private fun atualizarNavegacao() {
        icAnterior.alpha = if (posAtual > 0) 1f else 0.4f
        icProximo.alpha = if (temProximo()) 1f else 0.4f
    }

    // =========================================================
    // EPISÓDIOS
    // =========================================================

    private fun temProximo(): Boolean = !ehAoVivo && posAtual < epUrls.size - 1

    private fun atualizarBotaoProximo() {
        botaoProximo.visibility = if (temProximo() && fimDoEpisodio) View.VISIBLE else View.GONE
    }

    private fun cancelarContagem() {
        botaoProximo.removeCallbacks(contagemLoop)
        contagemProximo = 0
        fimDoEpisodio = false
        botaoProximo.text = "⏭  Próximo episódio"
        atualizarBotaoProximo()
    }

    // Marca o episódio como visto (mesmo formato de chave usado no MainActivity).
    private fun marcarVisto(chave: String) {
        if (chave.isBlank()) return
        try {
            val novo = HashSet(prefs.getStringSet("assistidos", emptySet()) ?: emptySet())
            for (k in chave.split('\u0001')) {
                if (k.isNotBlank()) novo.add(k)
            }
            prefs.edit().putStringSet("assistidos", novo).apply()
        } catch (_: Throwable) {
        }
    }

    private fun tocarProximo() {
        if (!temProximo()) {
            Toast.makeText(this, "Não há próximo episódio.", Toast.LENGTH_SHORT).show()
            return
        }
        tocarIndice(posAtual + 1)
    }

    private fun tocarAnterior() {
        val p = player
        if (ehAoVivo) return
        if (posAtual > 0 && (p == null || p.currentPosition < 5000L)) {
            tocarIndice(posAtual - 1)
        } else {
            p?.seekTo(0L)
        }
    }

    private fun tocarIndice(i: Int) {
        if (ehAoVivo || i !in epUrls.indices || i == posAtual) return

        cancelarContagem()
        salvarProgresso()

        posAtual = i
        val url = epUrls[i]
        val titulo = epTitulos[i]

        marcarVisto(epChaves[i])
        try {
            WolfProgress.registrar(this, url, titulo, intent.getStringExtra("VIDEO_COVER") ?: "")
        } catch (_: Throwable) {
        }

        urlAtualVideo = url
        tituloAtualVideo = titulo
        urlOriginal = url
        urlFinal = url
        jaTentouOriginal = false
        tentativasRede = 0
        erroTexto.visibility = View.GONE

        carregarMidia(0L)
        atualizarTitulo()
        atualizarNavegacao()
        atualizarLista()
    }

    private fun abrirEpisodios() {
        if (epUrls.size < 2) {
            Toast.makeText(this, "Sem outros episódios.", Toast.LENGTH_SHORT).show()
            return
        }
        val nomes = epTitulos.mapIndexed { i, t ->
            if (i == posAtual) "▶  $t" else "     $t"
        }.toTypedArray()
        mostrarDialogo(
            AlertDialog.Builder(this)
                .setTitle("Episódios")
                .setItems(nomes) { _, i -> tocarIndice(i) }
                .setNegativeButton("Fechar", null)
        )
    }

    private fun mostrarDialogo(b: AlertDialog.Builder) {
        playerView.removeCallbacks(esconderRunnable)
        b.setOnDismissListener {
            aplicarImersivo()
            agendarEsconder()
        }
        b.show()
    }

    // =========================================================
    // MINHA LISTA / COMPARTILHAR / VELOCIDADE / ESCALA
    // =========================================================

    // "Série - EP 3" / "Série - T2 EP 3" -> "Série" (mesmo nome usado nos favoritos)
    private fun tituloBase(): String =
        tituloAtualVideo.replace(Regex("\\s+-\\s+(T\\d+\\s+)?EP\\s+\\d+.*$"), "").trim()

    private fun estaNaLista(): Boolean =
        (prefs.getStringSet("favoritos", emptySet()) ?: emptySet()).contains(tituloBase())

    private fun atualizarLista() {
        if (!::btnLista.isInitialized) return
        iconeDaPilula(btnLista).tipo = if (estaNaLista()) "marcador_cheio" else "marcador"
        iconeDaPilula(btnLista).invalidate()
    }

    private fun alternarLista() {
        val novo = HashSet(prefs.getStringSet("favoritos", emptySet()) ?: emptySet())
        val t = tituloBase()
        if (t.isBlank()) return
        val agora = if (novo.contains(t)) {
            novo.remove(t)
            false
        } else {
            novo.add(t)
            true
        }
        prefs.edit().putStringSet("favoritos", novo).apply()
        atualizarLista()
        Toast.makeText(
            this,
            if (agora) "★ Adicionado à Minha Lista" else "Removido da Minha Lista",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun compartilhar() {
        abrindoEspelhamento = true
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Estou assistindo \"${tituloBase()}\" no WOLF IPTV 🐺")
        }
        startActivity(Intent.createChooser(i, "Compartilhar"))
    }

    private fun alternarVelocidade() {
        idxVelocidade = (idxVelocidade + 1) % velocidades.size
        val v = velocidades[idxVelocidade]
        player?.setPlaybackSpeed(v)
        textoVelocidade.text = "${v}x"
    }

    private fun alternarEscala() {
        val preenchendo = playerView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        playerView.resizeMode =
            if (preenchendo) AspectRatioFrameLayout.RESIZE_MODE_FIT
            else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        Toast.makeText(
            this,
            if (preenchendo) "Ajustar à tela" else "Preencher a tela",
            Toast.LENGTH_SHORT
        ).show()
    }

    // =========================================================
    // ÁUDIO E LEGENDAS
    // =========================================================

    private fun rotuloFaixa(format: Format, i: Int): String {
        val rotulo = format.label
        if (!rotulo.isNullOrBlank()) return rotulo
        val idioma = format.language
        if (!idioma.isNullOrBlank() && idioma != "und") {
            return Locale(idioma).displayLanguage.replaceFirstChar { it.uppercase() }
        }
        return "Faixa ${i + 1}"
    }

    private fun abrirFaixas(soLegendas: Boolean) {
        val p = player ?: return
        val itens = ArrayList<String>()
        val acoes = ArrayList<() -> Unit>()
        val grupos = p.currentTracks.groups

        if (!soLegendas) {
            for (g in grupos) {
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val sel = g.isTrackSelected(i)
                    itens.add((if (sel) "✓  " else "     ") + "Áudio: " + rotuloFaixa(g.getTrackFormat(i), i))
                    acoes.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        val textos = grupos.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textos.isNotEmpty()) {
            val algumaLigada = textos.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            itens.add((if (!algumaLigada) "✓  " else "     ") + "Legenda: Desativada")
            acoes.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            for (g in textos) {
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val sel = g.isTrackSelected(i)
                    itens.add((if (sel) "✓  " else "     ") + "Legenda: " + rotuloFaixa(g.getTrackFormat(i), i))
                    acoes.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        if (itens.isEmpty()) {
            Toast.makeText(
                this,
                if (soLegendas) "Este vídeo não tem legendas." else "Este vídeo não tem faixas extras.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        mostrarDialogo(
            AlertDialog.Builder(this)
                .setTitle(if (soLegendas) "Legendas" else "Áudio e Legendas")
                .setItems(itens.toTypedArray()) { _, i -> acoes[i]() }
                .setNegativeButton("Fechar", null)
        )
    }

    // =========================================================
    // FORMATO TS / M3U8
    // =========================================================

    private fun atualizarTextoFormato() {
        val m3u8 = urlFinal.contains(".m3u8", ignoreCase = true)
        botaoFormato.text = if (m3u8) "🔁  Formato: M3U8" else "🔁  Formato: TS"
    }

    private fun alternarFormato() {
        if (!ehAoVivo || !urlOriginal.endsWith(".ts", ignoreCase = true)) {
            Toast.makeText(this, "Só vale para canais ao vivo.", Toast.LENGTH_SHORT).show()
            return
        }

        usarM3u8 = !urlFinal.contains(".m3u8", ignoreCase = true)
        prefs.edit().putBoolean("ao_vivo_m3u8", usarM3u8).apply()

        jaTentouOriginal = false
        tentativasRede = 0
        urlFinal = calcularUrlFinal(urlOriginal)
        atualizarTextoFormato()
        erroTexto.visibility = View.GONE

        carregarMidia(0L)

        Toast.makeText(
            this,
            if (usarM3u8) "Formato: M3U8" else "Formato: TS",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun calcularUrlFinal(url: String): String {
        return if (ehAoVivo && usarM3u8 && url.endsWith(".ts", ignoreCase = true)) {
            url.dropLast(3) + ".m3u8"
        } else {
            url
        }
    }

    private fun carregarMidia(inicio: Long) {
        val builder = MediaItem.Builder().setUri(Uri.parse(urlFinal))

        if (urlFinal.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        if (inicio > 0L) {
            player?.setMediaItem(builder.build(), inicio)
        } else {
            player?.setMediaItem(builder.build())
        }

        player?.prepare()
        player?.playWhenReady = true
    }

    // =========================================================
    // FUNDO
    // =========================================================

    private fun fundoArredondado(cor: Int, raio: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(cor)
            cornerRadius = raio
            setStroke(2, Color.argb(100, 255, 255, 255))
        }
    }

    // =========================================================
    // BOTÕES ZOOM
    // =========================================================

    private fun criarBotoesZoom() {
        menuZoom.removeAllViews()

        val titulo = TextView(this)
        titulo.text = "⚙  Zoom da imagem"
        titulo.textSize = 20f
        titulo.setTextColor(Color.WHITE)
        titulo.gravity = Gravity.CENTER_VERTICAL
        titulo.setPadding(12, 0, 12, 14)

        menuZoom.addView(
            titulo,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 65)
        )

        for (i in nomesZoom.indices) {
            val botao = Button(this)
            botao.isFocusable = true
            botao.isClickable = true

            atualizarBotao(botao, i)

            botao.setOnFocusChangeListener { view, temFoco ->
                if (temFoco) {
                    view.background = fundoArredondado(vermelho, 16f)
                } else {
                    atualizarBotao(botao, i)
                }
            }

            botao.setOnClickListener {
                zoomAtual = i
                aplicarZoom()
                atualizarBotoes()
                botao.requestFocus()
            }

            menuZoom.addView(
                botao,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 62).apply {
                    setMargins(0, 4, 0, 4)
                }
            )
        }

        atualizarBotoes()
    }

    private fun atualizarBotoes() {
        for (i in nomesZoom.indices) {
            val view = menuZoom.getChildAt(i + 1)

            if (view is Button) {
                if (view.hasFocus()) {
                    view.background = fundoArredondado(vermelho, 16f)
                } else {
                    atualizarBotao(view, i)
                }
            }
        }
    }

    private fun atualizarBotao(botao: Button, indice: Int) {
        if (indice == zoomAtual) {
            botao.text = "✓   ${nomesZoom[indice]}"
            botao.setTextColor(Color.WHITE)
            botao.textSize = 17f
            botao.background = fundoArredondado(Color.rgb(55, 55, 55), 16f)
        } else {
            botao.text = "     ${nomesZoom[indice]}"
            botao.setTextColor(Color.LTGRAY)
            botao.textSize = 17f
            botao.background = fundoArredondado(Color.rgb(28, 28, 28), 16f)
        }
    }

    // =========================================================
    // TRANSMITIR PARA A TV
    // =========================================================

    private fun abrirEspelhamento() {
        val p = player
        Transmitir.mostrar(
            activity = this,
            titulo = tituloAtualVideo.ifBlank { "WOLF IPTV" },
            url = urlFinal,
            capa = intent.getStringExtra("VIDEO_COVER") ?: "",
            posicaoMs = p?.currentPosition ?: 0L,
            aoVivo = ehAoVivo,
            aoEspelhar = { abrindoEspelhamento = true }
        ) {
            // Transmissão começou: para o vídeo no celular.
            salvarProgresso()
            if (ehAoVivo) p?.stop() else p?.pause()
        }
    }

    // =========================================================
    // ABRIR / FECHAR MENU ZOOM
    // =========================================================

    private fun abrirMenuZoom() {
        menuAberto = true
        playerView.removeCallbacks(esconderRunnable)
        menuZoom.visibility = View.VISIBLE

        atualizarBotoes()

        if (menuZoom.childCount > 1) {
            menuZoom.getChildAt(zoomAtual + 1).post {
                menuZoom.getChildAt(zoomAtual + 1).requestFocus()
                atualizarBotoes()
            }
        }
    }

    private fun fecharMenuZoom() {
        menuAberto = false
        menuZoom.visibility = View.GONE
        btnEngrenagem.post { btnEngrenagem.requestFocus() }
        agendarEsconder()
    }

    // =========================================================
    // APLICAR ZOOM
    // =========================================================

    private fun aplicarZoom() {
        val escala = zooms[zoomAtual]
        val superficie = playerView.videoSurfaceView

        superficie?.let {
            it.pivotX = it.width / 2f
            it.pivotY = it.height / 2f

            it.scaleX = 1.0f
            it.scaleY = escala

            it.translationX = 0f
            it.translationY = 0f
        }
    }

    // =========================================================
    // CONTINUAR ASSISTINDO
    // =========================================================

    private fun salvarProgresso() {
        if (ehAoVivo) return

        try {
            val p = player ?: return
            val url = urlAtualVideo
            if (url.isBlank()) return
            val duracao = p.duration
            WolfProgress.salvar(this, url, p.currentPosition, if (duracao > 0) duracao else 0L)
        } catch (_: Throwable) {
        }
    }

    // =========================================================
    // RECONECTAR AO VIVO
    // =========================================================

    private fun reconectarAoVivo(atrasoMs: Long = 500L) {
        playerView.postDelayed({
            player?.seekToDefaultPosition()
            player?.prepare()
            player?.playWhenReady = true
        }, atrasoMs)
    }

    // =========================================================
    // INICIAR PLAYER
    // =========================================================

    private fun iniciarPlayer() {
        val url = intent.getStringExtra("VIDEO_URL")

        if (url.isNullOrBlank()) {
            mostrarErro("Nenhuma URL de vídeo foi recebida.")
            return
        }

        urlOriginal = url
        ehAoVivo = !url.contains("/movie/") && !url.contains("/series/")

        urlAtualVideo = url
        tituloAtualVideo = intent.getStringExtra("VIDEO_TITLE") ?: ""
        metaVideo = intent.getStringExtra("VIDEO_META") ?: ""

        // Lista: episódio atual + próximos (a fila vem do MainActivity).
        val fu = intent.getStringArrayExtra("FILA_URLS")?.toList() ?: emptyList()
        val ft = intent.getStringArrayExtra("FILA_TITULOS")?.toList() ?: emptyList()
        val fc = intent.getStringArrayExtra("FILA_CHAVES")?.toList() ?: emptyList()
        val n = minOf(fu.size, ft.size, fc.size)
        epUrls = mutableListOf(url)
        epTitulos = mutableListOf(tituloAtualVideo)
        epChaves = mutableListOf("")
        epUrls.addAll(fu.take(n))
        epTitulos.addAll(ft.take(n))
        epChaves.addAll(fc.take(n))
        posAtual = 0

        atualizarTitulo()
        aplicarModo()
        atualizarBotaoProximo()
        atualizarLista()

        urlFinal = calcularUrlFinal(url)

        if (ehAoVivo && urlOriginal.endsWith(".ts", ignoreCase = true)) {
            botaoFormato.visibility = View.VISIBLE
            atualizarTextoFormato()
        }

        var inicio = intent.getLongExtra("START_POSITION", 0L)

        if (ehAoVivo || urlFinal.contains(".m3u8", ignoreCase = true)) {
            inicio = 0L
        }

        try {
            val extratores = DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

            player =
                ExoPlayer.Builder(
                    this,
                    DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                )
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(this, extratores)
                            .setDataSourceFactory(
                                DefaultHttpDataSource.Factory()
                                    .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                                    .setConnectTimeoutMs(30_000)
                                    .setReadTimeoutMs(30_000)
                                    .setAllowCrossProtocolRedirects(true)
                            )
                    )
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                            .setTargetBufferBytes(12 * 1024 * 1024)
                            .setPrioritizeTimeOverSizeThresholds(false)
                            .build()
                    )
                    .build()

            playerView.player = player

            player?.addListener(
                object : Player.Listener {

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        atualizarIconePlay()
                        if (isPlaying) agendarEsconder()
                        else playerView.removeCallbacks(esconderRunnable)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                erroTexto.visibility = View.GONE
                            }

                            Player.STATE_READY -> {
                                cancelarContagem()
                                tentativasRede = 0
                                erroTexto.visibility = View.GONE

                                playerView.post {
                                    aplicarZoom()
                                    atualizarProgresso()
                                    atualizarIconePlay()
                                }
                            }

                            Player.STATE_ENDED -> {
                                if (ehAoVivo) {
                                    reconectarAoVivo(500L)
                                } else {
                                    salvarProgresso()
                                    atualizarIconePlay()

                                    if (temProximo()) {
                                        fimDoEpisodio = true
                                        mostrarControles()
                                        atualizarBotaoProximo()
                                        botaoProximo.requestFocus()
                                        if (segundosAutoProximo > 0) {
                                            botaoProximo.removeCallbacks(contagemLoop)
                                            contagemProximo = segundosAutoProximo
                                            botaoProximo.post(contagemLoop)
                                        }
                                    } else {
                                        mostrarControles()
                                    }
                                }
                            }

                            Player.STATE_IDLE -> {
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        try {
                            tratarErroPlayer(error)
                        } catch (e: Throwable) {
                            mostrarErro("Erro ao reproduzir:\n\n" + error.errorCodeName)
                        }
                    }

                    private fun tratarErroPlayer(error: PlaybackException) {
                        if (ehAoVivo && !jaTentouOriginal && urlFinal != urlOriginal) {
                            jaTentouOriginal = true
                            urlFinal = urlOriginal
                            atualizarTextoFormato()
                            player?.setMediaItem(MediaItem.fromUri(Uri.parse(urlOriginal)))
                            player?.prepare()
                            player?.playWhenReady = true
                            return
                        }

                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            player?.seekToDefaultPosition()
                            player?.prepare()
                            player?.playWhenReady = true
                            return
                        }

                        val erroDeRede =
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

                        val erroForaDoLimite =
                            error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                            (error.message?.contains("OUT_OF_RANGE", ignoreCase = true) == true)

                        if (erroForaDoLimite) {
                            mostrarErro("Canal instável ou posição inválida.\n\nA reiniciar do início...")

                            if (!ehAoVivo) {
                                WolfProgress.salvar(this@PlayerActivity, urlOriginal, 0L, 0L)
                            }

                            playerView.postDelayed({
                                player?.seekTo(0)
                                player?.prepare()
                                player?.playWhenReady = true
                            }, 1000)

                            return
                        }

                        if (erroDeRede && tentativasRede < maxTentativasRede) {
                            tentativasRede++

                            mostrarErro(
                                "Conexão instável.\n\n" +
                                "Reconectando... ($tentativasRede/$maxTentativasRede)"
                            )

                            playerView.postDelayed({
                                player?.prepare()
                                player?.playWhenReady = true
                            }, 2000)

                            return
                        }

                        mostrarErro("Erro ao reproduzir o vídeo:\n\n" + error.errorCodeName + "\n" + (error.cause?.message ?: ""))
                    }
                }
            )

            carregarMidia(inicio)

            playerView.postDelayed(salvarLoop, 5000)

            // Controles aparecem ao abrir e somem sozinhos depois de 5 s.
            mostrarControles()

        } catch (e: Exception) {
            mostrarErro("Erro ao abrir o player:\n\n" + e.message)
        }
    }

    // =========================================================
    // ERRO
    // =========================================================

    private fun mostrarErro(mensagem: String) {
        erroTexto.text = mensagem
        erroTexto.visibility = View.VISIBLE
    }

    // =========================================================
    // CONTROLE REMOTO / TECLADO
    // =========================================================

    private var zoomTeclaPressionada = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        // MENU DE ZOOM
        if (menuAberto) {
            when (event.keyCode) {

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {

                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount > 0 || zoomTeclaPressionada) {
                            return true
                        }
                        zoomTeclaPressionada = true
                        return true
                    }

                    if (event.action == KeyEvent.ACTION_UP) {
                        if (!zoomTeclaPressionada) {
                            return true
                        }

                        zoomTeclaPressionada = false

                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            moverFocoZoom(-1)
                        } else {
                            moverFocoZoom(1)
                        }

                        return true
                    }

                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        val foco = currentFocus
                        if (foco is Button) {
                            foco.performClick()
                        }
                    }
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        fecharMenuZoom()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        fecharMenuZoom()
                    }
                    return true
                }

                else -> {
                    return true
                }
            }
        }

        val tecla = event.keyCode

        // Voltar sai do player
        if (tecla == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) finish()
            return true
        }

        // Teclas de mídia
        if (event.action == KeyEvent.ACTION_UP) {
            when (tecla) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (temProximo()) {
                        tocarProximo()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    tocarAnterior()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    alternarPlay()
                    return true
                }
            }
        }

        val teclaNavegacao =
            tecla == KeyEvent.KEYCODE_DPAD_UP || tecla == KeyEvent.KEYCODE_DPAD_DOWN ||
                tecla == KeyEvent.KEYCODE_DPAD_LEFT || tecla == KeyEvent.KEYCODE_DPAD_RIGHT ||
                tecla == KeyEvent.KEYCODE_DPAD_CENTER || tecla == KeyEvent.KEYCODE_ENTER

        // Controles escondidos: a tecla faz a ação direta e mostra os controles
        if (!controlesVisiveis && teclaNavegacao) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (tecla) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> seekRelativo(10_000L)
                    KeyEvent.KEYCODE_DPAD_LEFT -> seekRelativo(-10_000L)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> alternarPlay()
                    else -> {}
                }
                mostrarControles()
            }
            return true
        }

        // Controles visíveis: o foco anda pelos botões e OK clica no botão focado
        if (teclaNavegacao) {
            agendarEsconder()
            // Foco perdido (preso no vídeo ou em nada): devolve pro play antes de navegar.
            if (!focoNosControles()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    icPlay.requestFocus()
                } else if (event.action == KeyEvent.ACTION_UP &&
                    (tecla == KeyEvent.KEYCODE_DPAD_CENTER || tecla == KeyEvent.KEYCODE_ENTER)
                ) {
                    alternarPlay()
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // =========================================================
    // MOVIMENTAR ZOOM
    // =========================================================

    private fun moverFocoZoom(direcao: Int) {
        val foco = currentFocus

        var indiceAtual = menuZoom.indexOfChild(foco)

        if (indiceAtual < 1) {
            indiceAtual = zoomAtual + 1
        }

        var novoIndice = indiceAtual + direcao

        if (novoIndice < 1) {
            novoIndice = menuZoom.childCount - 1
        }

        if (novoIndice >= menuZoom.childCount) {
            novoIndice = 1
        }

        val proximo = menuZoom.getChildAt(novoIndice)

        for (i in 1 until menuZoom.childCount) {
            menuZoom.getChildAt(i).isFocusable = false
        }

        proximo.isFocusable = true
        proximo.requestFocus()

        atualizarBotoes()

        menuZoom.post {
            for (i in 1 until menuZoom.childCount) {
                menuZoom.getChildAt(i).isFocusable = true
            }

            proximo.requestFocus()
            atualizarBotoes()
        }
    }

    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onResume() {
        super.onResume()
        abrindoEspelhamento = false
        if (player != null) {
            playerView.removeCallbacks(salvarLoop)
            playerView.postDelayed(salvarLoop, 5000)
            atualizarLista()
        }
    }

    override fun onPause() {
        playerView.removeCallbacks(salvarLoop)
        salvarProgresso()
        super.onPause()
    }

    override fun onStop() {
        playerView.removeCallbacks(salvarLoop)
        playerView.removeCallbacks(progressoLoop)
        playerView.removeCallbacks(esconderRunnable)
        cancelarContagem()
        salvarProgresso()
        if (!abrindoEspelhamento) player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        botaoProximo.removeCallbacks(contagemLoop)
        playerView.removeCallbacks(esconderRunnable)
        playerView.removeCallbacks(progressoLoop)
        salvarProgresso()
        playerView.removeCallbacks(salvarLoop)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
