package com.hush.env.android.content;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BField;

/**
 * Created by BlackBox on 2022/2/20.
 */
@BClassName("android.content.AttributionSourceState")
public interface AttributionSourceState {
    @BField
    String packageName();

    @BField
    int pid();

    @BField
    int uid();
}
