package com.yanhuozhang.offline.system

import kotlinx.coroutines.Deferred

/**
 * 跟踪“支付成功页读到了金额但缺商家、需要截图识别”的会话。
 *
 * - 同一笔支付只截一次图：页面持续显示，或离开后 [reuseWindowMillis] 内同金额页面又出现，
 *   都视为同一笔，沿用同一个 sourceEventId 和识别结果；
 * - 每个会话只入账一次；只有先按“无商家”入账、之后才拿到商家时，才允许再入账一次补全，
 *   与 [PaymentPageCaptureGate] 的签名语义一致，交给现有的同页补全逻辑处理。
 */
class MerchantOcrSessions(
    private val reuseWindowMillis: Long = 30_000L,
) {
    class Session internal constructor(
        val sourceEventId: String,
        val amountCents: Long,
        val startedAtMillis: Long,
    ) {
        /** 截图识别任务，结果为通过金额校验的商家名，失败为 null。 */
        var ocrResult: Deferred<String?>? = null
            internal set
        var decided = false
            private set
        var decidedMerchant: String? = null
            private set
        var ingested = false
            private set
        var ingestedMerchant: String? = null
            private set
        internal var absentSinceMillis: Long? = null
        internal var handOffStarted = false

        /** 首次决定后不再改变，唯一例外是之前没有商家、后来拿到了商家。 */
        internal fun decide(merchant: String?) {
            if (decided && (decidedMerchant != null || merchant == null)) {
                return
            }
            decided = true
            decidedMerchant = merchant
        }

        internal fun markIngested(merchant: String?) {
            ingested = true
            ingestedMerchant = merchant
        }
    }

    private var current: Session? = null

    /** 只查找可沿用的会话，不新建。节点已经读到商家时用它，避免无谓截图。 */
    fun find(amountCents: Long, nowMillis: Long): Session? {
        val session = current ?: return null
        if (session.amountCents != amountCents) {
            return null
        }
        val absentSinceMillis = session.absentSinceMillis
        if (absentSinceMillis != null && nowMillis - absentSinceMillis > reuseWindowMillis) {
            return null
        }
        session.absentSinceMillis = null
        return session
    }

    /** 查找或新建会话。新建时才调用 [newEventId]，保证 sourceEventId 与页面去重口径一致。 */
    fun obtain(amountCents: Long, nowMillis: Long, newEventId: () -> String): Session {
        find(amountCents, nowMillis)?.let { session -> return session }
        return Session(newEventId(), amountCents, nowMillis).also { session -> current = session }
    }

    /** 返回 true 表示这个会话还没截过图，调用方应当发起截图；之后同一会话永远返回 false。 */
    fun claimScreenshot(session: Session, ocrResult: () -> Deferred<String?>): Boolean {
        if (session.ocrResult != null) {
            return false
        }
        session.ocrResult = ocrResult()
        return true
    }

    fun decide(session: Session, merchant: String?): String? {
        session.decide(merchant)
        return session.decidedMerchant
    }

    fun shouldIngest(session: Session, merchant: String?): Boolean {
        return !session.ingested || (session.ingestedMerchant == null && merchant != null)
    }

    /** 先登记再执行挂起的入账，保证节点重试和离页兜底两条路径不会重复入账。 */
    fun claimIngest(session: Session, merchant: String?): Boolean {
        if (!shouldIngest(session, merchant)) {
            return false
        }
        session.markIngested(merchant)
        return true
    }

    /**
     * 页面离开时调用。返回仍未入账、需要在后台等识别结果再入账的会话；每个会话最多返回一次。
     */
    fun markPageAbsent(nowMillis: Long): Session? {
        val session = current ?: return null
        if (session.absentSinceMillis == null) {
            session.absentSinceMillis = nowMillis
        }
        if (session.ingested || session.handOffStarted) {
            return null
        }
        session.handOffStarted = true
        return session
    }
}
