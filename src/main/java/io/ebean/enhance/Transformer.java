package io.ebean.enhance;

import io.ebean.enhance.asm.ClassReader;
import io.ebean.enhance.asm.ClassWriter;
import io.ebean.enhance.asm.ClassWriterWithoutClassLoading;
import io.ebean.enhance.common.AgentManifest;
import io.ebean.enhance.common.AlreadyEnhancedException;
import io.ebean.enhance.common.ClassBytesReader;
import io.ebean.enhance.common.CommonSuperUnresolved;
import io.ebean.enhance.common.DetectEnhancement;
import io.ebean.enhance.common.EnhanceConstants;
import io.ebean.enhance.common.EnhanceContext;
import io.ebean.enhance.common.NoEnhancementRequiredException;
import io.ebean.enhance.common.TransformRequest;
import io.ebean.enhance.common.UrlPathHelper;
import io.ebean.enhance.entity.ClassAdapterEntity;
import io.ebean.enhance.entity.ClassPathClassBytesReader;
import io.ebean.enhance.entity.MessageOutput;
import io.ebean.enhance.querybean.TypeQueryClassAdapter;
import io.ebean.enhance.transactional.ClassAdapterTransactional;
import io.ebean.enhance.transactional.TransactionalMethodKey;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A Class file Transformer that performs Ebean enhancement of entity beans,
 * transactional methods and query bean enhancement.
 * <p>
 * This is used as both a java agent or via Maven and Gradle plugins etc.
 * </p>
 */
public class Transformer implements ClassFileTransformer {

  private static String version = "unknown";
  static {
    try {
      Properties prop = new Properties();
      InputStream in = Transformer.class.getResourceAsStream("/META-INF/maven/io.ebean/ebean-agent/pom.properties");
      if (in != null) {
        prop.load(in);
        in.close();
        version = prop.getProperty("version");
      }
      System.out.println("ebean-agent version: " + version);
    } catch (IOException e) {
      System.err.println("Could not determine ebean version: " +e.getMessage());
    }
  }
  
  public static String getVersion() {
    return version;
  }
  
  public static void premain(String agentArgs, Instrumentation inst) {

    Transformer transformer = new Transformer(null, agentArgs);
    inst.addTransformer(transformer);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
    premain(agentArgs, inst);
  }

  private final EnhanceContext enhanceContext;

  private final List<CommonSuperUnresolved> unresolved = new ArrayList<>();

  private boolean keepUnresolved;

  public Transformer(ClassLoader classLoader, String agentArgs) {
    if (classLoader == null) {
      classLoader = getClass().getClassLoader();
    }
    ClassBytesReader reader = new ClassPathClassBytesReader(null);
    AgentManifest manifest = AgentManifest.read(classLoader, null);
    this.enhanceContext = new EnhanceContext(reader, agentArgs, manifest);
  }

  /**
   * Create a transformer for entity bean enhancement and transactional method enhancement.
   *
   * @param bytesReader reads resources from class path for related inheritance and interfaces
   * @param agentArgs command line arguments for debug level etc
   */
  public Transformer(ClassBytesReader bytesReader, String agentArgs, AgentManifest manifest) {
    this.enhanceContext = new EnhanceContext(bytesReader, agentArgs, manifest);
  }

  /**
   * Set this to keep and report unresolved explicitly.
   */
  public void setKeepUnresolved() {
    this.keepUnresolved = true;
  }

  /**
   * Change the logout to something other than system out.
   */
  public void setLogout(MessageOutput logout) {
    this.enhanceContext.setLogout(logout);
  }

  public void log(int level, String msg) {
    log(level, null, msg);
  }

  private void log(int level, String className, String msg) {
    enhanceContext.log(level, className, msg);
  }

  public int getLogLevel() {
    return enhanceContext.getLogLevel();
  }

  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

    try {
      // ignore JDK and JDBC classes etc
      if (enhanceContext.isIgnoreClass(className)) {
        log(9, className, "ignore class");
        return null;
      }
      TransformRequest request = new TransformRequest(classfileBuffer);

      boolean isEbeanModel = className.equals(EnhanceConstants.EBEAN_MODEL);
      if (isEbeanModel || enhanceContext.detectEntityTransactionalEnhancement(className)) {
        try {
          DetectEnhancement detect = detect(loader, classfileBuffer);
  
          if (detect.isEntity()) {
            if (detect.isEnhancedEntity()) {
              detect.log(3, "already enhanced entity");
            } else {
              entityEnhancement(loader, request);
              log(8, className, "Entity Enhancement done");
            }
          }
  
          if (detect.isTransactional()) {
            if (detect.isEnhancedTransactional()) {
              detect.log(3, "already enhanced transactional");
            } else {
              transactionalEnhancement(loader, request);
              log(1, className, "Transactional Enhancement done");
            }
          }
        } catch (NoEnhancementRequiredException e) {
          log(8, className, "No Entity or Transactional Enhancement required " + e.getMessage());
        }
      }

      if (enhanceContext.detectQueryBeanEnhancement(className)) {
        try {
          enhanceQueryBean(loader, request);
          log(1, className, "Query Bean Enhancement done");
        } catch (NoEnhancementRequiredException e) {
          log(8, className, "No Querybean Enhancement required " + e.getMessage());
        }            
      }
     
      if (request.isEnhanced()) {
        return request.getBytes();
      } else {
        log(9, className, "no enhancement on class");
        return null;
      }
    } catch (Exception e) {
      enhanceContext.log(e);
      return null;
    } finally {
      logUnresolvedCommonSuper(className);
    }
  }

  /**
   * Return the transaction profiling keys.
   *
   * We use these to decode a the transaction profile.
   */
  public List<TransactionalMethodKey> getTransactionProfilingKeys() {
    return enhanceContext.getTransactionProfilingKeys();
  }

  /**
   * Log and common superclass classpath issues that defaulted to Object.
   */
  private void logUnresolvedCommonSuper(String className) {
    if (!keepUnresolved && !unresolved.isEmpty()) {
      for (CommonSuperUnresolved commonUnresolved : unresolved) {
        log(0, className, commonUnresolved.getMessage());
      }
      unresolved.clear();
    }
  }

  /**
   * Return the list of unresolved common superclass issues. This should be cleared
   * after each use and can only be used with {@link #setKeepUnresolved()}.
   */
  public List<CommonSuperUnresolved> getUnresolved() {
    return unresolved;
  }

  /**
   * Perform entity bean enhancement.
   */
  private void entityEnhancement(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriterWithoutClassLoading(ClassWriter.COMPUTE_FRAMES, loader);
    ClassAdapterEntity ca = new ClassAdapterEntity(cw, loader, enhanceContext);
    try {

      cr.accept(ca, ClassReader.EXPAND_FRAMES);

      if (ca.isLog(1)) {
        ca.logEnhanced();
      }

      request.enhancedEntity(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced entity");
      }
      request.enhancedEntity(null);

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(2)) {
        ca.log("skipping... no enhancement required");
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }

  /**
   * Perform transactional enhancement.
   */
  private void transactionalEnhancement(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriterWithoutClassLoading(ClassWriter.COMPUTE_FRAMES, loader);
    ClassAdapterTransactional ca = new ClassAdapterTransactional(cw, loader, enhanceContext);

    try {
      cr.accept(ca, ClassReader.EXPAND_FRAMES);

      if (ca.isLog(1)) {
        ca.log("enhanced transactional");
      }

      request.enhancedTransactional(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced");
      }

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(0)) {
        ca.log("skipping... no enhancement required");
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }


  /**
   * Perform enhancement.
   */
  private void enhanceQueryBean(ClassLoader loader, TransformRequest request) {

    ClassReader cr = new ClassReader(request.getBytes());
    ClassWriter cw = new ClassWriterWithoutClassLoading(ClassWriter.COMPUTE_FRAMES, loader);
    TypeQueryClassAdapter ca = new TypeQueryClassAdapter(cw, enhanceContext);

    try {
      cr.accept(ca, ClassReader.EXPAND_FRAMES);
      if (ca.isLog(9)) {
        ca.log("... completed");
      }
      request.enhancedQueryBean(cw.toByteArray());

    } catch (AlreadyEnhancedException e) {
      if (ca.isLog(1)) {
        ca.log("already enhanced");
      }

    } catch (NoEnhancementRequiredException e) {
      if (ca.isLog(9)) {
        ca.log("... skipping, no enhancement required: " + e.getMessage());
      }
    } finally {
      unresolved.addAll(cw.getUnresolved());
    }
  }

  /**
   * Helper method to split semi-colon separated class paths into a URL array.
   */
  public static URL[] parseClassPaths(String extraClassPath) {

    if (extraClassPath == null) {
      return new URL[0];
    }

    return UrlPathHelper.convertToUrl(extraClassPath.split(";"));
  }

  /**
   * Read the bytes quickly trying to detect if it needs entity or transactional
   * enhancement.
   */
  private DetectEnhancement detect(ClassLoader classLoader, byte[] classfileBuffer) {

    DetectEnhancement detect = new DetectEnhancement(classLoader, enhanceContext);

    ClassReader cr = new ClassReader(classfileBuffer);
    cr.accept(detect, ClassReader.SKIP_CODE + ClassReader.SKIP_DEBUG + ClassReader.SKIP_FRAMES);
    return detect;
  }
}
