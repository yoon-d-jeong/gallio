package com.example.gallio.presentation

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

// ───────── 문장 공격성 AI ─────────
// 음성인식으로 받은 문장이 "공격적인 말(욕설·하대·갑질·위협)"일 확률을 0~1 로 계산
// 학습: train_text_model.py (공개 혐오표현 데이터 + 매장 진상 문장)
// 방식: 1~3글자 조각(n-gram) TF-IDF + 로지스틱 회귀 → 별도 라이브러리 없이 계산 가능
// 모델 파일: app/src/main/assets/text_model.tsv
object TextAbuseModel {

    private const val FILE = "text_model.tsv"
    private val KEEP = Regex("[^가-힣ㄱ-ㅎㅏ-ㅣa-z0-9]")   // 학습할 때와 똑같은 전처리

    private var features: HashMap<String, FloatArray>? = null   // 글자조각 → [idf, 가중치]
    private var bias = 0.0
    private var minN = 1
    private var maxN = 3
    @Volatile var threshold = 0.5      // 이 확률 이상이면 "공격적인 문장"
        private set
    @Volatile private var failed = false

    val ready: Boolean get() = features != null

    // 처음 한 번만 불러옴 (1초 정도 걸릴 수 있어서 백그라운드에서 호출)
    @Synchronized
    fun load(context: Context): Boolean {
        if (features != null) return true
        if (failed) return false
        return try {
            val map = HashMap<String, FloatArray>(60000)
            context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { r ->
                val meta = JSONObject(r.readLine())
                bias = meta.getDouble("bias")
                minN = meta.getInt("ngram_min")
                maxN = meta.getInt("ngram_max")
                threshold = meta.getDouble("threshold")
                while (true) {
                    val line = r.readLine() ?: break
                    val a = line.indexOf('\t')
                    val b = line.indexOf('\t', a + 1)
                    if (a <= 0 || b <= a) continue
                    map[line.substring(0, a)] = floatArrayOf(
                        line.substring(a + 1, b).toFloat(),
                        line.substring(b + 1).toFloat()
                    )
                }
            }
            features = map
            true
        } catch (e: Exception) {
            failed = true   // 파일이 없으면 문장 AI 없이 단어 목록만으로 동작
            false
        }
    }

    // 문장 하나의 공격 확률. 긴 문장은 짧게 잘라서도 보고 가장 높은 값을 씀
    // (긴 평범한 말 속에 섞인 한 마디를 놓치지 않으려고)
    fun score(text: String): Double {
        val t = text.lowercase().replace(KEEP, "")
        if (t.isEmpty()) return 0.0
        var best = raw(t)
        if (t.length > 24) {
            var i = 0
            while (i < t.length) {
                best = maxOf(best, raw(t.substring(i, minOf(t.length, i + 20))))
                i += 10
            }
        }
        return best
    }

    private fun raw(t: String): Double {
        val f = features ?: return 0.0
        val counts = HashMap<String, Int>()
        for (n in minN..maxN) {
            for (k in 0..t.length - n) {
                val g = t.substring(k, k + n)
                if (f.containsKey(g)) counts[g] = (counts[g] ?: 0) + 1
            }
        }
        var sq = 0.0
        var dot = 0.0
        for ((g, c) in counts) {
            val v = f.getValue(g)
            val x = c * v[0].toDouble()
            sq += x * x
            dot += x * v[1]
        }
        val z = bias + if (sq > 0) dot / sqrt(sq) else 0.0
        return 1.0 / (1.0 + exp(-z))
    }
}