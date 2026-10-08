package com.shici.app

import android.app.Application
import com.shici.app.data.LearningRepository
import java.util.concurrent.Executors

class ShiciApplication : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val repository by lazy { LearningRepository(this) }
}
