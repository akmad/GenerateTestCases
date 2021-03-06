package com.intellij.generatetestcases.inspection;

import com.intellij.codeInspection.*;
import com.intellij.generatetestcases.model.BDDCore;
import com.intellij.generatetestcases.model.TestClass;
import com.intellij.generatetestcases.model.TestMethod;
import com.intellij.generatetestcases.model.GenerateTestCasesSettings;
import com.intellij.generatetestcases.model.TestMethodImpl;
import com.intellij.generatetestcases.quickfix.*;
import com.intellij.generatetestcases.testframework.SupportedFrameworks;
import com.intellij.generatetestcases.util.BddUtil;
import com.intellij.generatetestcases.util.Constants;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.jsp.jspJava.*;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.util.*;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * This inspection will search for clases not tested yet, and it will inspect should annotations
 * without a test method created
 */
public class MissingTestMethodInspection extends BaseJavaLocalInspectionTool {

      private static final Logger LOG = Logger.getInstance("#com.intellij.generatetestcases.inspection.MissingTestMethodInspection");

    @Nls
    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "BDD";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Nls
    @NotNull
    @Override
    public String getDisplayName() {
        return "Unused Should Annotations";
    }

    @NotNull
    @Override
    public String getShortName() {
        return "UnusedShould";
    }


    /**
     * @param aClass
     * @param manager
     * @param isOnTheFly
     * @return
     * @should create problem for classes without backing class
     * @should create problem for should annotations without test methods
     * @should ignore unsupported psiClasses
     *
     */
    @Override
    public ProblemDescriptor[] checkClass(@NotNull PsiClass aClass, @NotNull InspectionManager manager, boolean isOnTheFly) {

        // ignoring unsupported classes
        if (aClass instanceof PsiAnonymousClass || aClass instanceof JspClass) {
            return null;
        }

        if (aClass.getQualifiedName() == null) { // TODO research the API for the best way to make this check
            // aClass.getQualifiedName() is returning null just after writting an anonymous class, it is somehow related to the inner stub
            // see https://github.com/skarootz/GenerateTestCases/issues/27

            return ProblemDescriptor.EMPTY_ARRAY;
        }

        Project project = aClass.getProject();

        String testFramework;
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            testFramework = GenerateTestCasesSettings.getInstance(project).getTestFramework();
            if (StringUtils.isEmpty(testFramework)) {
                return null;
            }
        } else {
            testFramework = Constants.DEF_TEST_FRAMEWORK;
        }

        //  create TestClass for current class
        TestClass testClass = BDDCore.createTestClass(aClass, SupportedFrameworks.getStrategyForFramework(project, testFramework));


        //  highlight warning should cover test class name
        //  if test class doesn't exists place warning at class level
        if (!testClass.reallyExists()) {
            //  create warning
            return new ProblemDescriptor[]{
                    manager.createProblemDescriptor(testClass.getClassUnderTest().getNameIdentifier(), "Missing Test Class",
                            isOnTheFly, LocalQuickFix.EMPTY_ARRAY, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)};
        }

        List<ProblemDescriptor> result = new ArrayList<ProblemDescriptor>();

        //  if test class exists place warning at javadoc tags level
        List<TestMethod> methods = testClass.getAllMethods();
        for (TestMethod method : methods) {
            if (!method.reallyExists()) {
                highlightShouldTags(manager, isOnTheFly, result, method);
            }
        }

        // TODO create fix for this problem
        return result.toArray(new ProblemDescriptor[result.size()]);
    }

    private void highlightShouldTags(InspectionManager manager, boolean isOnTheFly, List<ProblemDescriptor> result, TestMethod method) {
        PsiDocTag backingTag = ((TestMethodImpl) method).getBackingTag();
        List<BddUtil.DocOffsetPair> elementPairsInDocTag = BddUtil.getElementPairsInDocTag(backingTag);
        final CreateTestMethodFix createTestMethodFix = new CreateTestMethodFix(method);

        for (BddUtil.DocOffsetPair docOffsetPair : elementPairsInDocTag) {

            LocalQuickFix localQuickFix = new LocalQuickFix() {
                @NotNull
                public String getName() {
                    return createTestMethodFix.getText();
                }

                public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
                    final PsiElement psiElement = descriptor.getPsiElement();
                    try {
                        LOG.assertTrue(psiElement.isValid());
                        createTestMethodFix.invoke();
                    } catch (IncorrectOperationException e) {
                        LOG.error(e);
                    }
                }

                @NotNull
                public String getFamilyName() {
                    return createTestMethodFix.getFamilyName();
                }
            };

            ProblemDescriptor problemDescriptor = manager.createProblemDescriptor(docOffsetPair.getStart(), docOffsetPair.getEnd(),
                        "Missing test method for should annotation", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, localQuickFix==null?null:new LocalQuickFix[]{localQuickFix});
                result.add(problemDescriptor);
        }
    }
}
