package io.nekobox.next.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.nekobox.next.db.MIGRATION_1_2
import io.nekobox.next.db.NekoDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NekoDatabase =
        Room.databaseBuilder(context, NekoDatabase::class.java, "nekobox.db")
            .addMigrations(MIGRATION_1_2)
            .build()
}
