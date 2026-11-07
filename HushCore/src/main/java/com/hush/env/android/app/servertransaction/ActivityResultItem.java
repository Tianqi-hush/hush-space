package com.hush.env.android.app.servertransaction;

import java.util.List;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

@BClassName("android.app.servertransaction.ActivityResultItem")
public interface ActivityResultItem {
    @BField
    List mResultInfoList();
}
