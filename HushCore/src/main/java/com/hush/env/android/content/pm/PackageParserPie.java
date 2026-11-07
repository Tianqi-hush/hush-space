package com.hush.env.android.content.pm;


import android.content.pm.PackageParser;

import com.hush.reflection.annotation.BClassName;
import com.hush.reflection.annotation.BStaticMethod;

@BClassName("android.content.pm.PackageParser")
public interface PackageParserPie {
    @BStaticMethod
    void collectCertificates(PackageParser.Package p, boolean skipVerify);
}
