// IBXposedManagerService.aidl

package com.hush.core.system.pm;

import java.util.List;
import com.hush.entity.pm.InstalledModule;

interface IBXposedManagerService {
    boolean isXPEnable();
    void setXPEnable(boolean enable);
    boolean isModuleEnable(String packageName);
    void setModuleEnable(String packageName, boolean enable);
    List<InstalledModule> getInstalledModules();
    /** 模块作用域；返回空列表表示全部应用。 */
    List<String> getModuleScope(String packageName);
    /** 保存模块作用域；传空列表表示恢复为全部应用。 */
    void setModuleScope(String packageName, in List<String> scope);
}