package com.anytypeio.anytype.apibridge

import com.anytypeio.anytype.core_models.Relations
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.di.common.ComponentDependencies
import com.anytypeio.anytype.domain.account.AwaitAccountStartManager
import com.anytypeio.anytype.domain.auth.interactor.LaunchAccount
import com.anytypeio.anytype.domain.auth.interactor.LaunchWallet
import com.anytypeio.anytype.domain.auth.repo.AuthRepository
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.block.repo.BlockRepository
import com.anytypeio.anytype.domain.config.ConfigStorage
import com.anytypeio.anytype.domain.config.UserSettingsRepository
import com.anytypeio.anytype.domain.device.PathProvider
import com.anytypeio.anytype.domain.launch.PreferredSpaceIdHolder
import com.anytypeio.anytype.domain.multiplayer.SpaceViewSubscriptionContainer
import com.anytypeio.anytype.domain.`object`.FetchObject
import com.anytypeio.anytype.domain.platform.InitialParamsProvider
import com.anytypeio.anytype.domain.workspace.SpaceManager
import com.anytypeio.anytype.middleware.localapi.LocalApiControl
import dagger.Component
import dagger.Lazy
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Builds the [ApiBridgeInstaller]. The app creates it once per process, in
 * `Application.onCreate`. The adapters hold their middleware dependencies through [Lazy], and
 * [LocalApiControl] only wraps the gomobile calls, so building the component starts nothing.
 */
@Component(
    dependencies = [ApiBridgeDependencies::class],
    modules = [ApiBridgeModule::class]
)
@Singleton
interface ApiBridgeComponent {

    fun installer(): ApiBridgeInstaller

    @Component.Factory
    interface Factory {
        fun create(dependencies: ApiBridgeDependencies): ApiBridgeComponent
    }
}

@Module
object ApiBridgeModule {

    @JvmStatic
    @Provides
    @Singleton
    fun provideGateway(): LocalApiGateway = LocalApiControlGateway(LocalApiControl())

    @JvmStatic
    @Provides
    @Singleton
    fun provideLauncher(
        authRepository: Lazy<AuthRepository>,
        configStorage: Lazy<ConfigStorage>,
        awaitAccountStartManager: AwaitAccountStartManager,
        launchWallet: Lazy<LaunchWallet>,
        launchAccount: Lazy<LaunchAccount>,
        fetchObject: Lazy<FetchObject>
    ): AccountLauncher = MiddlewareAccountLauncher(
        auth = authRepository,
        configStorage = configStorage,
        awaitAccountStart = awaitAccountStartManager,
        launchWallet = launchWallet,
        launchAccount = launchAccount,
        profileName = {
            configStorage.get().getOrNull()?.let { config ->
                fetchObject.get().async(
                    FetchObject.Params(
                        space = SpaceId(config.techSpace),
                        obj = config.profile,
                        keys = listOf(Relations.ID, Relations.NAME)
                    )
                ).getOrNull()?.name
            }
        }
    )

    @JvmStatic
    @Provides
    @Singleton
    fun provideSpaces(
        container: Lazy<SpaceViewSubscriptionContainer>
    ): SpaceDirectory = SpaceViewDirectory(container)

    @JvmStatic
    @Provides
    @Singleton
    fun provideLaunchWallet(
        authRepository: AuthRepository,
        pathProvider: PathProvider
    ): LaunchWallet = LaunchWallet(
        repository = authRepository,
        pathProvider = pathProvider
    )

    @JvmStatic
    @Provides
    @Singleton
    fun provideLaunchAccount(
        authRepository: AuthRepository,
        pathProvider: PathProvider,
        configStorage: ConfigStorage,
        spaceManager: SpaceManager,
        initialParamsProvider: InitialParamsProvider,
        userSettings: UserSettingsRepository,
        awaitAccountStartManager: AwaitAccountStartManager,
        preferredSpaceIdHolder: PreferredSpaceIdHolder
    ): LaunchAccount = LaunchAccount(
        repository = authRepository,
        pathProvider = pathProvider,
        configStorage = configStorage,
        spaceManager = spaceManager,
        initialParamsProvider = initialParamsProvider,
        settings = userSettings,
        awaitAccountStartManager = awaitAccountStartManager,
        preferredSpaceIdHolder = preferredSpaceIdHolder
    )

    @JvmStatic
    @Provides
    @Singleton
    fun provideFetchObject(
        repo: BlockRepository,
        dispatchers: AppCoroutineDispatchers
    ): FetchObject = FetchObject(repo = repo, dispatchers = dispatchers)
}

interface ApiBridgeDependencies : ComponentDependencies {
    fun spaceViewSubscriptionContainer(): SpaceViewSubscriptionContainer
    fun authRepository(): AuthRepository
    fun pathProvider(): PathProvider
    fun configStorage(): ConfigStorage
    fun spaceManager(): SpaceManager
    fun metricsProvider(): InitialParamsProvider
    fun userSettingsRepository(): UserSettingsRepository
    fun awaitAccountStartManager(): AwaitAccountStartManager
    fun preferredSpaceIdHolder(): PreferredSpaceIdHolder
    fun blockRepository(): BlockRepository
    fun dispatchers(): AppCoroutineDispatchers
}
