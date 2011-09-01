package net.jangaroo.ide.idea.exml;

import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.facet.FacetManager;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.SourceGeneratingCompiler;
import com.intellij.openapi.compiler.ValidityState;
import com.intellij.openapi.compiler.ex.CompileContextEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import net.jangaroo.exml.ExmlConstants;
import net.jangaroo.exml.ExmlcException;
import net.jangaroo.exml.compiler.Exmlc;
import net.jangaroo.exml.config.ExmlConfiguration;
import net.jangaroo.exml.generator.ExmlConfigClassGenerator;
import net.jangaroo.exml.model.ConfigClass;
import net.jangaroo.ide.idea.AbstractCompiler;
import net.jangaroo.jooc.JangarooParser;
import net.jangaroo.jooc.Jooc;
import net.jangaroo.utils.CompilerUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 *
 */
public class ExmlCompiler extends AbstractCompiler implements SourceGeneratingCompiler {
  private static final GenerationItem[] EMPTY_GENERATION_ITEM_ARRAY = {};

  public ExmlCompiler() {
    super();
  }

  @NotNull
  public String getDescription() {
    return "EXML Compiler";
  }

  public boolean isCompilableFile(VirtualFile file, CompileContext context) {
    if (ExmlConstants.EXML_SUFFIX.equals("." + file.getExtension())) {
      Module module = context.getModuleByFile(file);
      if (module != null && FacetManager.getInstance(module).getFacetByType(ExmlFacetType.ID) != null) {
        return true;
      }
    }
    return false;
  }

  public static ExmlcConfigurationBean getExmlConfig(Module module) {
    ExmlFacet exmlFacet = FacetManager.getInstance(module).getFacetByType(ExmlFacetType.ID);
    return exmlFacet == null ? null : exmlFacet.getConfiguration().getState();
  }

  static String getXsdFilename(Module module) {
    if (module != null) {
      ExmlcConfigurationBean exmlcConfig = getExmlConfig(module);
      if (exmlcConfig != null) {
        return exmlcConfig.getGeneratedResourcesDirectory() + "/" + exmlcConfig.getConfigClassPackage() + ".xsd";
      }
    }
    return null;
  }

  static String findDependentModuleZipFileName(OrderEntry orderEntry) throws IOException {
    VirtualFile[] files = orderEntry.getFiles(OrderRootType.CLASSES);
    // check that library is not empty:
    for (VirtualFile file : files) {
      // TODO: make it work for classes, not only for jars!
      String filename = file.getPath();
      if (filename.endsWith("!/")) { // it is a jar:
        return filename.substring(0, filename.length() - "!/".length());
      }
    }
    return null;
  }

  static ZipEntry findXsdZipEntry(ZipFile zipFile) throws IOException {
    // find a *.xsd in jar's root folder:
    Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
    while (enumeration.hasMoreElements()) {
      ZipEntry zipEntry = enumeration.nextElement();
      if (!zipEntry.isDirectory() && zipEntry.getName().indexOf('/') == -1 && zipEntry.getName().endsWith(".xsd")) {
        return zipEntry;
      }
    }
    return null;
  }

  @Override
  protected String getOutputFileSuffix() {
    return Jooc.AS_SUFFIX;
  }

  public VirtualFile getPresentableFile(CompileContext context, Module module, VirtualFile outputRoot, VirtualFile generatedFile) {
    return null; // TODO: return EXML/properties file for AS compile errors? Why is this not called?
  }

  public ValidityState createValidityState(DataInput in) throws IOException {
    return null;
  }

  public GenerationItem[] getGenerationItems(CompileContext context) {
    return ApplicationManager.getApplication().runReadAction(new PrepareAction(context));
  }

  public GenerationItem[] generate(CompileContext context, GenerationItem[] items, VirtualFile outputRootDirectory) {
    if (items.length == 0) {
      return EMPTY_GENERATION_ITEM_ARRAY;
    }
    Module module = items[0].getModule();
    ExmlcConfigurationBean exmlcConfigurationBean = getExmlConfig(module);
    ExmlConfiguration exmlConfiguration = new ExmlConfiguration();
    List<VirtualFile> files = new ArrayList<VirtualFile>(items.length);
    for (GenerationItem item : items) {
      files.add(((JooGenerationItem)item).getSourceFile());
    }
    updateFileLocations(exmlConfiguration, module, files);
    String generatedSourcesDirectory = exmlcConfigurationBean.getGeneratedSourcesDirectory();
    exmlConfiguration.setOutputDirectory(new File(generatedSourcesDirectory));
    exmlConfiguration.setResourceOutputDirectory(new File(exmlcConfigurationBean.getGeneratedResourcesDirectory()));
    exmlConfiguration.setConfigClassPackage(exmlcConfigurationBean.getConfigClassPackage());
    List<GenerationItem> successfullyGeneratedItems = new ArrayList<GenerationItem>(items.length); // TODO!
    Exmlc exmlc = new Exmlc(exmlConfiguration);
    for (GenerationItem generationItem : items) {
      JooGenerationItem jooGenerationItem = (JooGenerationItem)generationItem;
      VirtualFile virtualSourceFile = jooGenerationItem.getSourceFile();
      File sourceFile = VfsUtil.virtualToIoFile(virtualSourceFile);
      try {
        if (jooGenerationItem.isConfigClass()) {
          exmlc.generateConfigClass(sourceFile);
        } else {
          exmlc.generateComponentClass(sourceFile);
        }
        successfullyGeneratedItems.add(generationItem);
      } catch (ExmlcException e) {
        context.addMessage(CompilerMessageCategory.ERROR, e.getLocalizedMessage(), LocalFileSystem.getInstance().findFileByIoFile(e.getSource()).getUrl(), e.getLine(), e.getColumn());
      }
    }
    for (GenerationItem item : successfullyGeneratedItems) {
      CompilerUtil.refreshIOFile(new File(exmlConfiguration.getOutputDirectory(), item.getPath()));
    }
    if (context instanceof CompileContextEx) {
      List<VirtualFile> generatedVFiles = new ArrayList<VirtualFile>();
      for (GenerationItem item : successfullyGeneratedItems) {
        VirtualFile generatedVFile = LocalFileSystem.getInstance().findFileByPath(exmlConfiguration.getOutputDirectory().getPath() + "/" + item.getPath());
        if (generatedVFile != null) {
          generatedVFiles.add(generatedVFile);
        }
      }
      ((CompileContextEx)context).markGenerated(generatedVFiles);
    }
    return successfullyGeneratedItems.toArray(new GenerationItem[successfullyGeneratedItems.size()]);
  }

  static Logger getLog() {
    return Logger.getInstance("ExmlCompiler");
  }

  // TODO: copied from Jangaroo CompilerUtils 0.8.7-SNAPSHOT, remove when updating:
  private static String getRelativePath(File baseDirectory, File file) {
    String relativePath = null;
    try {
      String canonicalBasePath = baseDirectory.getCanonicalPath() + File.separator;
      String canonicalPath = file.getCanonicalPath();
      if (canonicalPath.length() > canonicalBasePath.length() &&
        canonicalPath.startsWith(canonicalBasePath)) {
        relativePath = canonicalPath.substring(canonicalBasePath.length());
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("could not determine qualified name from file; the strange file is called " + file + " in " + baseDirectory, e);
    }
    return relativePath;
  }

  private final class PrepareAction implements Computable<GenerationItem[]> {
    private final CompileContext context;

    public PrepareAction(CompileContext context) {
      this.context = context;
    }

    public GenerationItem[] compute() {
      if (context.getProject().isDisposed()) {
        return EMPTY_GENERATION_ITEM_ARRAY;
      }
      VirtualFile[] files = context.getProjectCompileScope().getFiles(XmlFileType.INSTANCE, true);
      List<VirtualFile> exmlFiles = new ArrayList<VirtualFile>(files.length);
      for (VirtualFile file : files) {
        if (isCompilableFile(file, context)) {
          exmlFiles.add(file);
        }
      }
      List<GenerationItem> items = new ArrayList<GenerationItem>(exmlFiles.size());
      final Map<Module, List<VirtualFile>> filesByModule = CompilerUtil.buildModuleToFilesMap(context, exmlFiles.toArray(new VirtualFile[exmlFiles.size()]));
      for (Map.Entry<Module,List<VirtualFile>> entry : filesByModule.entrySet()) {
        Module module = entry.getKey();
        ExmlcConfigurationBean exmlcConfigurationBean = getExmlConfig(module);
        ExmlConfiguration exmlConfiguration = new ExmlConfiguration();
        updateFileLocations(exmlConfiguration, module, entry.getValue());
        String generatedSourcesDirectory = exmlcConfigurationBean.getGeneratedSourcesDirectory();
        exmlConfiguration.setOutputDirectory(new File(generatedSourcesDirectory));
        exmlConfiguration.setResourceOutputDirectory(new File(exmlcConfigurationBean.getGeneratedResourcesDirectory()));
        exmlConfiguration.setConfigClassPackage(exmlcConfigurationBean.getConfigClassPackage());
        ExmlConfigClassGenerator exmlConfigClassGenerator = new ExmlConfigClassGenerator(exmlConfiguration);
        for (VirtualFile file : entry.getValue()) {
          try {
            // TODO: make this a reusable method in ExmlConfigClassGenerator:
            File generatedConfigClassFile = exmlConfigClassGenerator.computeConfigClassTarget(ConfigClass.createConfigClassName(file.getNameWithoutExtension()));
            addItem(file, generatedConfigClassFile, true, module, exmlConfiguration, items);

            File exmlFile = VfsUtil.virtualToIoFile(file);
            // TODO: refactor ExmlComponentClassGenerator to make this easier and encapsulate logic:
            String qName = CompilerUtils.qNameFromFile(exmlConfiguration.findSourceDir(exmlFile), exmlFile);
            File generatedComponentClassFile = CompilerUtils.fileFromQName(qName, exmlConfiguration.getOutputDirectory(), JangarooParser.AS_SUFFIX);
            addItem(file, generatedComponentClassFile, false, module, exmlConfiguration, items);
          } catch (IOException e) {
            e.printStackTrace();
            // TODO
          }
        }
      }
      return items.toArray(new GenerationItem[items.size()]);
    }

    private void addItem(VirtualFile file,
                         File generatedFile, boolean configClass, Module module, ExmlConfiguration exmlConfiguration,
                         List<GenerationItem> items) throws IOException {
      ProjectFileIndex fileIndex = ProjectRootManager.getInstance(module.getProject()).getFileIndex();
      File sourceDir = exmlConfiguration.findSourceDir(generatedFile);
      String generatedFilePath = getRelativePath(sourceDir, generatedFile).replace(File.separatorChar, '/');
      JooGenerationItem generationItem =
        new JooGenerationItem(module, file, configClass, fileIndex.isInTestSourceContent(file), generatedFilePath);
      if (context.isMake()) {
        if (generatedFile == null || !generatedFile.exists() || generatedFile.lastModified() <= file.getModificationCount()) {
          // TODO: createSourceRootIfNotExist(sourceRootPath, module);
          items.add(generationItem);
        }
      } else {
        // TODO: createSourceRootIfNotExist(sourceRootPath, module);
        items.add(generationItem);
      }
    }
  }

  private final static class JooGenerationItem implements GenerationItem {
    private final Module module;
    private final VirtualFile file;
    private final boolean testSource;
    private final String generatedFilePath;
    private final boolean configClass;

    public JooGenerationItem(@NotNull Module module,
                             @NotNull VirtualFile file,
                             boolean configClass,
                             boolean testSource,
                             @NotNull String generatedFilePath) {
      this.module = module;
      this.file = file;
      this.configClass = configClass;
      this.testSource = testSource;
      this.generatedFilePath = generatedFilePath;
    }

    public VirtualFile getSourceFile() {
      return file;
    }

    @Nullable
    public String getPath() {
      return generatedFilePath;
    }

    public boolean isConfigClass() {
      return configClass;
    }

    @Nullable
    public ValidityState getValidityState() {
      return null;
    }

    public Module getModule() {
      return module;
    }

    public boolean isTestSource() {
      return testSource;
    }
  }
  
}
