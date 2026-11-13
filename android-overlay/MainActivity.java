package com.legendary.hacks;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HackPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
