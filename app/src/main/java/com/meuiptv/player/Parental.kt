package com.meuiptv.player

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest

/**
 * Controle parental: uma senha numérica e, por conta/lista, as pastas (categorias)
 * bloqueadas de cada seção. Depois de digitar a senha certa, a pasta fica liberada
 * até o app ser fechado.
 */
object Parental {

    private val unlocked = HashSet<String>()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("iptv_parental", Context.MODE_PRIVATE)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("iptv:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin(ctx: Context): Boolean = !prefs(ctx).getString("pin", null).isNullOrEmpty()

    fun checkPin(ctx: Context, pin: String): Boolean =
        pin.isNotEmpty() && prefs(ctx).getString("pin", null) == hash(pin)

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString("pin", hash(pin)).apply()
    }

    /** Desativa o controle parental: apaga a senha e todos os bloqueios. */
    fun disable(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        unlocked.clear()
    }

    private fun lockKey(kind: Kind) = "lock|${kind.name}|" + Store.scope()

    fun locked(ctx: Context, kind: Kind): Set<String> =
        if (!hasPin(ctx)) emptySet()
        else prefs(ctx).getStringSet(lockKey(kind), emptySet()) ?: emptySet()

    fun setLocked(ctx: Context, kind: Kind, ids: Set<String>) {
        prefs(ctx).edit().putStringSet(lockKey(kind), HashSet(ids)).apply()
    }

    fun isBlocked(kind: Kind, categoryId: String, locked: Set<String>): Boolean =
        locked.contains(categoryId) && !unlocked.contains("${kind.name}:$categoryId")

    fun unlock(kind: Kind, categoryId: String) {
        unlocked.add("${kind.name}:$categoryId")
    }

    /** Pede a senha. Chama [onOk] se estiver certa; [onCancel] se o usuário desistir. */
    fun askPin(act: Activity, title: String, onOk: () -> Unit, onCancel: (() -> Unit)? = null) {
        val et = EditText(act)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "Senha"
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = FrameLayout(act)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(et)

        val dlg = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancelar") { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()

        dlg.setOnShowListener {
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                if (checkPin(act, et.text.toString())) {
                    dlg.dismiss()
                    onOk()
                } else {
                    et.setText("")
                    et.error = "Senha incorreta"
                }
            }
            et.setOnEditorActionListener { _, _, _ ->
                ok.performClick()
                true
            }
            et.requestFocus()
        }
        dlg.show()
    }
}
