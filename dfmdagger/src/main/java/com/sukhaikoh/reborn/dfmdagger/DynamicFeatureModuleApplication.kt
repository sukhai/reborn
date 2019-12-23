/*
 * Copyright (C) 2019 Su Khai Koh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sukhaikoh.reborn.dfmdagger

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import androidx.fragment.app.Fragment
import dagger.android.AndroidInjection
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import javax.inject.Inject

/**
 * An [Application] that injects its members and can be used to inject [Activity]s,
 * [Fragment]s, [Service]s, [BroadcastReceiver]s and [ContentProvider]s attached to it. Injection
 * is performed in [Application.onCreate] or the first call to [AndroidInjection.inject], whichever
 * happens first.
 *
 * An [Application] that injects its members and loading different project modules whenever they
 * are needed by calling [FeatureModuleInjector.inject].
 *
 * For example, before navigating from Activity1 to Activity2, Activity1 must call
 * [FeatureModuleInjector.inject] with Activity2's [FeatureModule] before calling
 * [Activity.startActivity]. Failure to do so will result in the dagger dependency graph in
 * Activity2 module not link to this main application's dagger graph, hence some dependencies
 * in Acitivty2 might not be resolved.
 *
 * Here are other members this [Application] can inject:
 * * [Activity]
 * * [Fragment]
 * * [Service]
 * * [BroadcastReceiver]
 * * [ContentProvider]
 *
 * ### Example
 * ```
 * class MyApp : DynamicFeatureModuleApplication() {
 *     override fun applicationInjector(): AndroidInjector<out DynamicFeatureModuleApplication> {
 *         return DaggerAppComponent.builder()
 *             .application(this)
 *             .build()
 *     }
 * }
 *
 * @Singleton
 * @Component(
 *     modules = [
 *         AndroidInjectionModule::class,
 *         AppModule::class
 *     ]
 * )
 * interface AppComponent : AndroidInjector<MyApp> {
 *     @Component.Builder
 *     interface Builder {
 *         @BindsInstance
 *         fun application(application: Application): Builder
 *
 *         fun build(): AppComponent
 *     }
 * }
 *
 * @Module
 * class AppModule {
 *     // ...
 * }
 * ```
 */
abstract class DynamicFeatureModuleApplication : Application(), HasAndroidInjector {

    @Inject
    lateinit var dispatchingAndroidInjector: DispatchingAndroidInjector<Any>

    @Volatile
    private var needToInject = true
    private val injectedModules = mutableSetOf<FeatureModule>()

    val component by lazy { applicationInjector() }

    private val androidModuleInjectors = mutableListOf<DispatchingAndroidInjector<Any>>()

    private val androidInjector by lazy {
        Injector(
            dispatchingAndroidInjector,
            androidModuleInjectors
        )
    }

    override fun onCreate() {
        super.onCreate()

        injectIfNeeded()
    }

    protected abstract fun applicationInjector(): AndroidInjector<out DynamicFeatureModuleApplication>

    override fun androidInjector(): AndroidInjector<Any> {
        return androidInjector
    }

    @Inject
    internal fun setInjected() {
        needToInject = false
    }

    fun inject(module: FeatureModule) {
        if (injectedModules.contains(module)) {
            return
        }

        val clazz = Class.forName(module.injectorName)
        val moduleInjector = clazz.newInstance() as FeatureModuleInjector
        moduleInjector.inject(this)

        androidModuleInjectors.add(moduleInjector.androidInjector())
    }

    private fun injectIfNeeded() {
        if (needToInject) {
            synchronized(this) {
                if (needToInject) {
                    @Suppress("UNCHECKED_CAST")
                    (component as AndroidInjector<DynamicFeatureModuleApplication>)
                        .inject(this)
                    check(!needToInject) {
                        "The AndroidInjector returned from applicationInjector() did not inject " +
                            "the DynamicFeatureModuleApplication"
                    }
                }
            }
        }
    }

    private data class Injector<T>(
        private val currentInjector: DispatchingAndroidInjector<T>,
        private val otherInjectors: List<DispatchingAndroidInjector<T>>
    ) : AndroidInjector<T> {
        override fun inject(instance: T) {
            // We first try to inject by using the injector in the current module
            if (currentInjector.maybeInject(instance)) {
                return
            }

            // Then try to inject using injector from other modules
            otherInjectors.forEach { injector ->
                if (injector.maybeInject(instance)) {
                    return
                }
            }
            throw IllegalStateException("Injector not found for $instance")
        }
    }
}