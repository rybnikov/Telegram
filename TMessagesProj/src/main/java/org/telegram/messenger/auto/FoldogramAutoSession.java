package org.telegram.messenger.auto;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.places.PlaceDetailsResolver;

public class FoldogramAutoSession extends Session {

    private final int currentAccount = UserConfig.selectedAccount;
    private final AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
    private final AutoSessionConnectionLease connectionLease = new AutoSessionConnectionLease(currentAccount);
    private AutoAvatarProvider avatarProvider;
    private AutoGeoRepository geoRepository;
    private AutoMessagePreviewRepository messagePreviewRepository;
    private AutoDialogsRepository dialogsRepository;
    private AutoVoiceRecorderController voiceRecorderController;
    private AutoSpeechController speechController;
    private AutoPlacesRepository placesRepository;
    private AutoPlaceItemFactory placeItemFactory;
    private AutoConversationItemFactory conversationItemFactory;
    private boolean initialized;

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        if (!initialized) {
            initialized = true;
            avatarProvider = new AutoAvatarProvider(currentAccount, accountInstance);
            geoRepository = new AutoGeoRepository(accountInstance, () -> {
            });
            messagePreviewRepository = new AutoMessagePreviewRepository(accountInstance, () -> {
            });
            dialogsRepository = new AutoDialogsRepository(currentAccount, accountInstance, avatarProvider, geoRepository, messagePreviewRepository);
            geoRepository.setOnStateChanged(() -> dialogsRepository.scheduleRebuild(0L));
            messagePreviewRepository.setOnStateChanged(() -> dialogsRepository.scheduleRebuild(0L));
            voiceRecorderController = new AutoVoiceRecorderController(getCarContext(), currentAccount);
            speechController = new AutoSpeechController(getCarContext(), voiceRecorderController);
            PlaceDetailsResolver placeDetailsResolver = new PlaceDetailsResolver(
                    getCarContext().getApplicationContext(), currentAccount);
            placesRepository = new AutoPlacesRepository(
                    currentAccount, accountInstance, avatarProvider, placeDetailsResolver);
            placeItemFactory = new AutoPlaceItemFactory(getCarContext(), avatarProvider);
            conversationItemFactory = new AutoConversationItemFactory(
                    getCarContext(), currentAccount, accountInstance, avatarProvider, geoRepository,
                    messagePreviewRepository, voiceRecorderController);
            connectionLease.acquire();
            dialogsRepository.start();
            placesRepository.start();
            getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(@NonNull LifecycleOwner owner) {
                    voiceRecorderController.destroy();
                    speechController.destroy();
                    placesRepository.destroy();
                    dialogsRepository.destroy();
                    connectionLease.release();
                }
            });
        }
        return new ChatListScreen(getCarContext(), currentAccount, accountInstance,
                dialogsRepository, conversationItemFactory, voiceRecorderController, avatarProvider,
                placesRepository, placeItemFactory, speechController);
    }
}
