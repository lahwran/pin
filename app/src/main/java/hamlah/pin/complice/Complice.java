package hamlah.pin.complice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;

import hamlah.pin.App;
import hamlah.pin.BuildConfig;
import hamlah.pin.service.Settings;
import rx.Observable;
import rx.Subscriber;

public class Complice {
    private static final String TAG = Complice.class.getSimpleName();
    private static Complice instance;
    private CompliceAPI api = App.app().http().create(CompliceAPI.class);

    public static Complice get() {
        if (instance == null) {
            synchronized (Complice.class) {
                if (instance == null) {
                    instance = new Complice();
                }
            }
        }
        return instance;
    }


    public boolean isLoggedIn() {
        return new Settings(App.app()).getCompliceToken() != null;
    }

    // step 3: direct send POST https://complice.co/oauth/token?code=%3$s&grant_type=authorization_code&client_id=%1$s&username=%1$s&password=%2$s&client_secret=%2$s&redirect_uri=http://127.0.0.1/pinstickytimers/oauth
    // step 4: response: {"access_token":"...","token_type":"Bearer"}
    // step 5: save access token to settings
    //
    // POST /api/u/completeById/$ID

    public Observable<Boolean> completeLogin(Uri uri) {
        String token = uri.getQueryParameter("code");
        return api.authorize(token,
                    BuildConfig.CLIENT_ID,
                    BuildConfig.CLIENT_SECRET,
                    String.format("http://%1$s%2$s",
                            BuildConfig.REDIRECT_URI_HOST, BuildConfig.REDIRECT_URI_PATH))
                .map(authResponse -> {
                    Log.i(TAG, "Your password: " + authResponse.accessToken);
                    new Settings(App.app()).setCompliceToken(authResponse.accessToken);
                    return true;
                });

    }

    public Uri getLoginUrl() {
        return Uri.parse(String.format(
                "https://complice.co/oauth/authorize?response_type=code&client_id=%1$s&client_secret=%2$s&redirect_uri=http://%3$s%4$s",
                BuildConfig.CLIENT_ID,
                BuildConfig.CLIENT_SECRET,
                BuildConfig.REDIRECT_URI_HOST,
                BuildConfig.REDIRECT_URI_PATH
        ));
    }

    public void launchLogin(Context context) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(getLoginUrl());
        context.startActivity(i);
    }

    public Observable<CompliceRemoteTask> getNextAction() {
        Observable<CompliceRemoteTask> get = getAuthToken()
                .flatMap(api::loadCurrentTask)
                .map(currentTaskResponse -> {
                    CompliceRemoteTask result = new CompliceRemoteTask(
                            currentTaskResponse.colors != null
                                    ? currentTaskResponse.colors.getIntColor()
                                    : 0x666666,
                            currentTaskResponse.nextAction.text,
                            currentTaskResponse.nextAction.id,
                            currentTaskResponse.nextAction.goalCode);
                    new Settings(App.app()).setLastKnownRemoteTask(result);
                    return result;
                });
        CompliceRemoteTask task = new Settings(App.app()).getLastKnownRemoteTask();
        if (task != null) {
            return Observable.just(task).concatWith(get);
        } else {
            return get;
        }
    }

    @NonNull
    private Observable<String> getAuthToken() {
        String token = new Settings(App.app()).getCompliceToken();
        if (token == null) {
            return Observable.error(new RuntimeException("Not logged in lol"));
        }
        return Observable.just("Bearer " + token);
    }


    public Observable<String> finishAction(CompliceRemoteTask compliceRemoteTask) {
        return getAuthToken()
                .flatMap(token -> api.complete(token, compliceRemoteTask.getId()))
                .flatMap(r -> Observable.create(s -> {
                    try {
                        s.onNext(r.string());
                    } catch (IOException e) {
                        s.onError(e);
                    }
                    s.onCompleted();
                }));
    }
}