package com.hush.fake.frameworks;

import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

import com.hush.core.system.ServiceManager;
import com.hush.core.system.pm.IBXposedManagerService;
import com.hush.entity.pm.InstalledModule;

/**
 * Created by @officialakshit01 on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class BXposedManager extends BlackManager<IBXposedManagerService> {
    private static final BXposedManager sXposedManager = new BXposedManager();

    public static BXposedManager get() {
        return sXposedManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.XPOSED_MANAGER;
    }

    public boolean isXPEnable() {
        try {
            return getService().isXPEnable();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setXPEnable(boolean enable) {
        try {
            getService().setXPEnable(enable);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isModuleEnable(String packageName) {
        try {
            return getService().isModuleEnable(packageName);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setModuleEnable(String packageName, boolean enable) {
        try {
            getService().setModuleEnable(packageName, enable);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public List<InstalledModule> getInstalledModules() {
        try {
            return getService().getInstalledModules();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public List<String> getModuleScope(String packageName) {
        try {
            List<String> scope = getService().getModuleScope(packageName);
            return scope == null ? Collections.<String>emptyList() : scope;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public void setModuleScope(String packageName, List<String> scope) {
        try {
            getService().setModuleScope(packageName, scope);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
