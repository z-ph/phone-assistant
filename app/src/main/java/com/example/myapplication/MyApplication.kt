package com.example.myapplication

import android.app.Application
import android.content.Context
import com.example.myapplication.agent.LangChainAgentEngine
import com.example.myapplication.shell.ShellExecutor
import com.example.myapplication.utils.CrashHandler
import com.example.myapplication.utils.Logger

class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"

        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: synchronized(this) {
                instance ?: throw IllegalStateException("Application not initialized")
            }
        }

        fun getAppContext(): Context {
            return getInstance().applicationContext
        }

        fun getLangChainAgentEngine(): LangChainAgentEngine = getInstance().langChainAgentEngine
    }

    private val logger = Logger(TAG)

    val shellExecutor: ShellExecutor by lazy {
        logger.d("Creating ShellExecutor")
        ShellExecutor(applicationContext)
    }

    val langChainAgentEngine: LangChainAgentEngine by lazy {
        logger.d("Creating LangChainAgentEngine")
        LangChainAgentEngine.getInstance(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        logger.i("MyApplication created")
        initializeApp()
    }

    private fun initializeApp() {
        CrashHandler.init(applicationContext)
        logger.i("CrashHandler initialized")

        val filesDir = applicationContext.getExternalFilesDir(null)
        if (filesDir != null) {
            Logger.enableFileLogging(filesDir)
            logger.d("File logging enabled to ${filesDir.absolutePath}")
        }

        try {
            val initResult = langChainAgentEngine.initialize()
            if (initResult.isSuccess) {
                logger.i("LangChainAgentEngine initialized successfully")
            } else {
                logger.w("LangChainAgentEngine not configured: ${initResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.w("LangChainAgentEngine initialization failed: ${e.message}")
        }

        logger.i("Application initialized")
    }
}
