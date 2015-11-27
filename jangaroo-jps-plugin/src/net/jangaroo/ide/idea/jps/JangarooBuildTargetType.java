package net.jangaroo.ide.idea.jps;

import com.intellij.flex.FlexCommonUtils;
import com.intellij.flex.model.bc.JpsFlexBuildConfiguration;
import com.intellij.flex.model.bc.JpsFlexBuildConfigurationManager;
import com.intellij.flex.model.module.JpsFlexModuleType;
import com.intellij.openapi.util.Trinity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.BuildTargetLoader;
import org.jetbrains.jps.builders.BuildTargetType;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.module.JpsTypedModule;

import java.util.ArrayList;
import java.util.List;

public class JangarooBuildTargetType extends BuildTargetType<JangarooBuildTarget> {
  public static final JangarooBuildTargetType INSTANCE = new JangarooBuildTargetType();

  private JangarooBuildTargetType() {
    super("jangaroo");
  }

  @NotNull
  public List<JangarooBuildTarget> computeAllTargets(@NotNull JpsModel model) {
    List<JangarooBuildTarget> result = new ArrayList<JangarooBuildTarget>();
    for (JpsTypedModule<JpsFlexBuildConfigurationManager> module : model.getProject().getModules(JpsFlexModuleType.INSTANCE)) {
      List<JpsFlexBuildConfiguration> buildConfigurations = module.getProperties().getBuildConfigurations();
      for (JpsFlexBuildConfiguration bc : buildConfigurations) {
        result.add(JangarooBuildTarget.create(bc, null));
      }
    }
    return result;
  }

  @NotNull
  public BuildTargetLoader<JangarooBuildTarget> createLoader(@NotNull JpsModel model) {
    return new JangarooBuildTargetLoader(model);
  }

  private static class JangarooBuildTargetLoader extends BuildTargetLoader<JangarooBuildTarget> {
    private final JpsModel model;

    public JangarooBuildTargetLoader(JpsModel model) {
      this.model = model;
    }

    @Nullable
    public JangarooBuildTarget createTarget(@NotNull String buildTargetId) {
      Trinity trinity = FlexCommonUtils.getModuleAndBCNameAndForcedDebugStatusByBuildTargetId(buildTargetId);
      if(trinity != null) {
        String moduleName = (String)trinity.first;
        String bcName = (String)trinity.second;
        Boolean forcedDebugStatus = (Boolean)trinity.third;
        for (JpsTypedModule<JpsFlexBuildConfigurationManager> module : model.getProject().getModules(JpsFlexModuleType.INSTANCE)) {
          if(module.getName().equals(moduleName)) {
            JpsFlexBuildConfiguration bc = module.getProperties().findConfigurationByName(bcName);
            return bc != null ? JangarooBuildTarget.create(bc, forcedDebugStatus) : null;
          }
        }
      }

      return null;
    }
  }
}