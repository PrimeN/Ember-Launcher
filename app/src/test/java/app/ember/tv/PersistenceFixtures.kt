package app.ember.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.ember.tv.data.BackupSecrets
import java.io.File
import java.lang.reflect.Proxy

internal class PersistenceContext(private val directory:File):ContextWrapper(null) {
    private val stores=linkedMapOf<String,MemoryPreferences>()
    override fun getApplicationContext():Context=this
    override fun getFilesDir()=directory.also {it.mkdirs()}
    override fun getCacheDir()=File(directory,"cache").also {it.mkdirs()}
    override fun getSharedPreferences(name:String,mode:Int)=memory(name).preferences
    @Synchronized fun memory(name:String)=stores.getOrPut(name){MemoryPreferences()}
}

internal class MemoryPreferences {
    private val values=linkedMapOf<String,Any>()
    var failures=0
    private fun snapshot()=synchronized(this) {values.mapValues {(_,v)->if(v is Set<*>)v.toSet() else v}}
    val preferences:SharedPreferences=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) {_,method,args->
        when(method.name) {
            "edit"->editor()
            "getAll"->snapshot()
            "contains"->synchronized(this){values.containsKey(args!![0])}
            "getString","getBoolean","getInt","getLong","getFloat","getStringSet"->snapshot()[args!![0]] ?: args.getOrNull(1)
            "registerOnSharedPreferenceChangeListener","unregisterOnSharedPreferenceChangeListener"->null
            "toString"->"Fixture preferences"
            else->error("Unexpected preference call ${method.name}")
        }
    } as SharedPreferences
    private fun editor():SharedPreferences.Editor {
        val changes=linkedMapOf<String,Any?>();var clear=false
        return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) {proxy,method,args->
            when {
                method.name.startsWith("put")->{changes[args!![0] as String]=args[1];proxy}
                method.name=="remove"->{changes[args!![0] as String]=null;proxy}
                method.name=="clear"->{clear=true;proxy}
                method.name=="commit" || method.name=="apply"->{
                    val success=synchronized(this) {
                        if(clear)values.clear()
                        changes.forEach {(key,value)->if(value==null)values.remove(key) else values[key]=if(value is Set<*>)value.toSet() else value}
                        if(failures>0){failures--;false} else true
                    }
                    if(method.name=="commit")success else null
                }
                else->error("Unexpected editor call ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}

internal class FixtureSecrets(initial:Map<String,String> = emptyMap(),override val encrypted:Boolean=true):BackupSecrets {
    private var values=initial.toMap()
    var failNext=false
    override fun read()=values.toMap()
    override fun replace(values:Map<String,String>) {
        this.values=values.toMap()
        if(failNext){failNext=false;error("simulated storage failure")}
    }
}
