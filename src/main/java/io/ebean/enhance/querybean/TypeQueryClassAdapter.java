package io.ebean.enhance.querybean;

import io.ebean.enhance.common.AlreadyEnhancedException;
import io.ebean.enhance.common.EnhanceContext;
import io.ebean.enhance.common.NoEnhancementRequiredException;
import io.ebean.enhance.asm.AnnotationVisitor;
import io.ebean.enhance.asm.ClassVisitor;
import io.ebean.enhance.asm.ClassWriter;
import io.ebean.enhance.asm.FieldVisitor;
import io.ebean.enhance.asm.MethodVisitor;
import io.ebean.enhance.asm.Opcodes;

/**
 * Reads/visits the class and performs the appropriate enhancement if necessary.
 */
public class TypeQueryClassAdapter extends ClassVisitor implements Constants {

  private final EnhanceContext enhanceContext;

  private boolean typeQueryRootBean;
  private String className;
  private String signature;
  private ClassInfo classInfo;

  public TypeQueryClassAdapter(ClassWriter cw, EnhanceContext enhanceContext) {
    super(Opcodes.ASM5, cw);
    this.enhanceContext = enhanceContext;
  }

  @Override
  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {

    super.visit(version, access, name, signature, superName, interfaces);
    if ((access & Opcodes.ACC_INTERFACE) != 0) {
      throw new NoEnhancementRequiredException("Not enhancing interface");
    }
    if (hasEntityBeanInterface(interfaces)) {
      throw new NoEnhancementRequiredException("Not enhancing EntityBean");
    }
    this.typeQueryRootBean = TQ_ROOT_BEAN.equals(superName);
    this.className = name;
    this.signature = signature;
    this.classInfo = new ClassInfo(enhanceContext, name);
  }

  /**
   * Return true if this case the EntityBean interface.
   */
  private boolean hasEntityBeanInterface(String[] interfaces) {
    for (int i = 0; i < interfaces.length; i++) {
      if (interfaces[i].equals(C_ENTITYBEAN)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extract and return the associated entity bean class from the signature.
   */
  protected String getDomainClass() {
    int posStart = signature.indexOf('<');
    int posEnd = signature.indexOf(';', posStart + 1);
    return signature.substring(posStart + 2, posEnd);
  }

  /**
   * Look for TypeQueryBean annotation.
   */
  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    classInfo.checkTypeQueryAnnotation(desc);
    return super.visitAnnotation(desc, visible);
  }

  @Override
  public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
    if (classInfo.isAlreadyEnhanced()) {
      throw new AlreadyEnhancedException(className);
    }
    if (classInfo.isTypeQueryBean()) {
      // collect type query bean fields
      classInfo.addField(access, name, desc, signature);
    }
    return super.visitField(access, name, desc, signature, value);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {

    if (classInfo.isTypeQueryBean()) {
      if ((access & Opcodes.ACC_STATIC) != 0) {
        if (isLog(5)) {
          log("ignore static methods on type query bean " +name + " " + desc);
        }
        return super.visitMethod(access, name, desc, signature, exceptions);
      }
      if (classInfo.addMarkerAnnotation()) {
        addMarkerAnnotation();
      }
      if (name.equals("<init>")) {
        if (!typeQueryRootBean) {
          return handleAssocBeanConstructor(access, name, desc, signature, exceptions);
        }
        if (isLog(3)) {
          log("replace constructor code <init> " + desc);
        }
        if (desc.equals("(Z)V")) {
          // Constructor for alias initialises all the properties/fields
          return new TypeQueryConstructorForAlias(classInfo, cv);
        }
        return new TypeQueryConstructorAdapter(classInfo, getDomainClass(), cv, desc, signature);
      }
      if (!desc.startsWith("()L")) {
        if (isLog(5)) {
          log("leaving method as is - " + name + " " + desc + " " + signature);
        }
        return super.visitMethod(access, name, desc, signature, exceptions);
      }
    }

    if (isLog(8)) {
      log("... checking method " + name + " " + desc);
    }
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    return new MethodAdapter(mv, enhanceContext, classInfo);
  }

  /**
   * Handle the constructors for assoc type query beans.
   */
  private MethodVisitor handleAssocBeanConstructor(int access, String name, String desc, String signature, String[] exceptions) {

    if (desc.equals(ASSOC_BEAN_BASIC_CONSTRUCTOR_DESC)) {
      classInfo.setHasBasicConstructor();
      return new TypeQueryAssocBasicConstructor(classInfo, cv, desc, signature);
    }
    if (desc.equals(ASSOC_BEAN_MAIN_CONSTRUCTOR_DESC)) {
      classInfo.setHasMainConstructor();
      return new TypeQueryAssocMainConstructor(classInfo, cv, desc, signature);
    }
    // leave as is
    return super.visitMethod(access, name, desc, signature, exceptions);
  }

  @Override
  public void visitEnd() {
    if (classInfo.isAlreadyEnhanced()) {
      throw new AlreadyEnhancedException(className);
    }

    if (classInfo.isTypeQueryBean()) {
      if (!typeQueryRootBean) {
        classInfo.addAssocBeanExtras(cv);
      }
      TypeQueryAddMethods.add(cv, classInfo, typeQueryRootBean);
      if (isLog(2)) {
        classInfo.log("enhanced as type query bean");
      }
    } else if (classInfo.isTypeQueryUser()) {
      if (isLog(1)) {
        classInfo.log("enhanced - getfield calls replaced");
      }
    } else {
      throw new NoEnhancementRequiredException("Not a type bean or caller of type beans");
    }
    super.visitEnd();
  }

  /**
   * Add the marker annotation so that we don't enhance the type query bean twice.
   */
  private void addMarkerAnnotation() {

    if (isLog(4)) {
      log("... adding marker annotation");
    }
    AnnotationVisitor av = cv.visitAnnotation(ANNOTATION_ALREADY_ENHANCED_MARKER, true);
    if (av != null) {
      av.visitEnd();
    }
  }

  public boolean isLog(int level) {
    return enhanceContext.isLog(level);
  }

  public void log(String msg) {
    enhanceContext.log(className, msg);
  }
}
