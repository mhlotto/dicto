package com.mhlotto.dicto

import android.app.Application
import com.mhlotto.dicto.data.DictoDatabase
import com.mhlotto.dicto.data.DictoRepository
import com.mhlotto.dicto.speech.DictationEngineFactory
import com.mhlotto.dicto.speech.DictationEngineSettings

class DictoApplication : Application() {
    val dictationEngineSettings: DictationEngineSettings by lazy {
        DictationEngineSettings(this)
    }

    val dictationEngineFactory: DictationEngineFactory by lazy {
        DictationEngineFactory(this, dictationEngineSettings)
    }

    val repository: DictoRepository by lazy {
        val database = DictoDatabase.get(this)
        DictoRepository(database.projectDao(), database.noteDao())
    }
}
