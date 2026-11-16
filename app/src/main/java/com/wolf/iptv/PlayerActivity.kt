APENAS 2 TRECHOS PARA COLAR NO SEU PlayerActivity ATUAL (não substitua o arquivo todo)

=== TRECHO 1 ===
No dispatchKeyEvent, troque este bloco:

        // PLAYER NORMAL
        if (event.action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }

por este:

        val foco = currentFocus
        val focoNosControles = foco != null && foco !== playerView
        val ehDpad = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (ehDpad) {
                // Controles aparecendo (ex.: no começo do filme) mas sem nada focado:
                // o primeiro toque do controle só coloca o foco no botão de play.
                if (controlesVisiveis && !focoNosControles) {
                    focarControles()
                    return true
                }

                // Controles escondidos: setas ←/→ pulam 10s, o resto só mostra os controles.
                if (!controlesVisiveis) {
            
(o "when (event.keyCode) {" que vem depois continua igual)

=== TRECHO 2 ===
Cole estas funções em qualquer lugar da classe:

    // FOCO NOS CONTROLES
    // =========================================================

    private fun ehDoPlayer(v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === playerView) return true
            p = p.parent as? View
        }
        return false
    }

    private fun focarControles() {
        for (nome in listOf("exo_play_pause", "exo_play", "exo_pause", "exo_settings")) {
            val id = resources.getIdentifier(nome, "id", packageName)
            if (id == 0) continue
            val v = playerView.findViewById<View>(id) ?: continue
            if (v.visibility == View.VISIBLE && v.isFocusable && v.requestFocus()) return
        }
        // Layout personalizado (Episódios, Minha Lista, etc.): foca o primeiro botão visível.
        val lista = ArrayList<View>()
        playerView.addFocusables(lista, View.FOCUS_DOWN)
        lista.firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
    }

    // =========================================================
