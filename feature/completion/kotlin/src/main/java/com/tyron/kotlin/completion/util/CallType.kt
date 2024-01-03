/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Cosmic IDE. If not, see <https://www.gnu.org/licenses/>.
 */

/*
 *  This file is part of CodeAssist.
 *
 *  CodeAssist is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeAssist is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with CodeAssist.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tyron.kotlin.completion.util

import com.intellij.psi.PsiElement
import com.tyron.kotlin.completion.resolve.ResolutionFacade
import com.tyron.kotlin.completion.resolve.frontendService
import com.tyron.kotlin.completion.resolve.getDataFlowValueFactory
import com.tyron.kotlin.completion.resolve.getLanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.isFinalOrEnum
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.descriptors.JavaCallableMemberDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.psi.psiUtil.isImportDirectiveExpression
import org.jetbrains.kotlin.psi.psiUtil.isPackageDirectiveExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfoBefore
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.SmartCastManager
import org.jetbrains.kotlin.resolve.descriptorUtil.classValueType
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindExclude
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.TypeAliasQualifier
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.types.expressions.DoubleColonLHS
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

sealed class CallType<TReceiver : KtElement?>(val descriptorKindFilter: DescriptorKindFilter) {
    object UNKNOWN : CallType<Nothing?>(DescriptorKindFilter.ALL)

    object DEFAULT : CallType<Nothing?>(DescriptorKindFilter.ALL)

    object DOT : CallType<KtExpression>(DescriptorKindFilter.ALL)

    object SAFE : CallType<KtExpression>(DescriptorKindFilter.ALL)

    object SUPER_MEMBERS : CallType<KtSuperExpression>(
        DescriptorKindFilter.CALLABLES exclude DescriptorKindExclude.Extensions exclude AbstractMembersExclude
    )

    object INFIX : CallType<KtExpression>(DescriptorKindFilter.FUNCTIONS exclude NonInfixExclude)

    object OPERATOR :
        CallType<KtExpression>(DescriptorKindFilter.FUNCTIONS exclude NonOperatorExclude)

    object CALLABLE_REFERENCE :
        CallType<KtExpression?>(DescriptorKindFilter.CALLABLES exclude CallableReferenceExclude)

    object IMPORT_DIRECTIVE : CallType<KtExpression?>(DescriptorKindFilter.ALL)

    object PACKAGE_DIRECTIVE : CallType<KtExpression?>(DescriptorKindFilter.PACKAGES)

    object TYPE : CallType<KtExpression?>(
        DescriptorKindFilter(DescriptorKindFilter.CLASSIFIERS_MASK or DescriptorKindFilter.PACKAGES_MASK)
                exclude DescriptorKindExclude.EnumEntry
    )

    object DELEGATE :
        CallType<KtExpression?>(DescriptorKindFilter.FUNCTIONS exclude NonOperatorExclude)

    object ANNOTATION : CallType<KtExpression?>(
        DescriptorKindFilter(DescriptorKindFilter.CLASSIFIERS_MASK or DescriptorKindFilter.PACKAGES_MASK)
                exclude NonAnnotationClassifierExclude
    )

    private object NonInfixExclude : DescriptorKindExclude() {
        override fun excludes(descriptor: DeclarationDescriptor) =
            !(descriptor is SimpleFunctionDescriptor && descriptor.isInfix)

        override val fullyExcludedDescriptorKinds: Int
            get() = 0
    }

    private object NonOperatorExclude : DescriptorKindExclude() {
        override fun excludes(descriptor: DeclarationDescriptor) =
            !(descriptor is SimpleFunctionDescriptor && descriptor.isOperator)

        override val fullyExcludedDescriptorKinds: Int
            get() = 0
    }

    private object CallableReferenceExclude : DescriptorKindExclude() {
        override fun excludes(descriptor: DeclarationDescriptor) /* currently not supported for locals and synthetic */ =
            descriptor !is CallableMemberDescriptor || descriptor.kind == CallableMemberDescriptor.Kind.SYNTHESIZED

        override val fullyExcludedDescriptorKinds: Int
            get() = 0
    }

    private object NonAnnotationClassifierExclude : DescriptorKindExclude() {
        override fun excludes(descriptor: DeclarationDescriptor): Boolean {
            if (descriptor !is ClassifierDescriptor) return false
            return descriptor !is ClassDescriptor || descriptor.kind != ClassKind.ANNOTATION_CLASS
        }

        override val fullyExcludedDescriptorKinds: Int get() = 0
    }

    private object AbstractMembersExclude : DescriptorKindExclude() {
        override fun excludes(descriptor: DeclarationDescriptor) =
            descriptor is CallableMemberDescriptor && descriptor.modality == Modality.ABSTRACT

        override val fullyExcludedDescriptorKinds: Int
            get() = 0
    }
}

data class ReceiverType(
    val type: KotlinType,
    val receiverIndex: Int,
    val implicitValue: ReceiverValue? = null
)

fun CallTypeAndReceiver<*, *>.receiverTypes(
    bindingContext: BindingContext,
    contextElement: PsiElement,
    moduleDescriptor: ModuleDescriptor,
    resolutionFacade: ResolutionFacade,
    stableSmartCastsOnly: Boolean
): List<KotlinType>? {
    return receiverTypesWithIndex(
        bindingContext,
        contextElement,
        moduleDescriptor,
        resolutionFacade,
        stableSmartCastsOnly
    )?.map { it.type }
}

fun CallTypeAndReceiver<*, *>.receiverTypesWithIndex(
    bindingContext: BindingContext,
    contextElement: PsiElement,
    moduleDescriptor: ModuleDescriptor,
    resolutionFacade: ResolutionFacade,
    stableSmartCastsOnly: Boolean,
    withImplicitReceiversWhenExplicitPresent: Boolean = false
): List<ReceiverType>? {
    val languageVersionSettings = resolutionFacade.getLanguageVersionSettings()

    val receiverExpression: KtExpression?
    when (this) {
        is CallTypeAndReceiver.CALLABLE_REFERENCE -> {
            if (receiver != null) {
                return when (val lhs = bindingContext[BindingContext.DOUBLE_COLON_LHS, receiver]
                    ?: return emptyList()) {
                    is DoubleColonLHS.Type -> listOf(ReceiverType(lhs.type, 0))

                    is DoubleColonLHS.Expression -> {
                        val receiverValue =
                            ExpressionReceiver.create(receiver, lhs.type, bindingContext)
                        receiverValueTypes(
                            receiverValue, lhs.dataFlowInfo, bindingContext,
                            moduleDescriptor, stableSmartCastsOnly,
                            resolutionFacade
                        ).map { ReceiverType(it, 0) }
                    }
                }
            } else {
                return emptyList()
            }
        }

        is CallTypeAndReceiver.DEFAULT -> receiverExpression = null

        is CallTypeAndReceiver.DOT -> receiverExpression = receiver
        is CallTypeAndReceiver.SAFE -> receiverExpression = receiver
        is CallTypeAndReceiver.INFIX -> receiverExpression = receiver
        is CallTypeAndReceiver.OPERATOR -> receiverExpression = receiver
        is CallTypeAndReceiver.DELEGATE -> receiverExpression = receiver

        is CallTypeAndReceiver.SUPER_MEMBERS -> {
            val qualifier = receiver.superTypeQualifier
            return if (qualifier != null) {
                listOfNotNull(bindingContext.getType(receiver)).map { ReceiverType(it, 0) }
            } else {
                val resolutionScope =
                    contextElement.getResolutionScope(bindingContext, resolutionFacade)
                val classDescriptor =
                    resolutionScope.ownerDescriptor.parentsWithSelf.firstIsInstanceOrNull<ClassDescriptor>()
                        ?: return emptyList()
                classDescriptor.typeConstructor.supertypesWithAny().map { ReceiverType(it, 0) }
            }
        }

        is CallTypeAndReceiver.IMPORT_DIRECTIVE,
        is CallTypeAndReceiver.PACKAGE_DIRECTIVE,
        is CallTypeAndReceiver.TYPE,
        is CallTypeAndReceiver.ANNOTATION,
        is CallTypeAndReceiver.UNKNOWN ->
            return null
    }

    val resolutionScope = contextElement.getResolutionScope(bindingContext, resolutionFacade)

    fun extractReceiverTypeFrom(descriptor: ClassDescriptor): KotlinType? =
        // companion object type or class itself
        descriptor.classValueType
            ?: if (descriptor.isFinalOrEnum || descriptor is JavaClassDescriptor || descriptor is JavaCallableMemberDescriptor) null else descriptor.defaultType

    fun tryExtractReceiver(context: BindingContext) =
        context.get(BindingContext.QUALIFIER, receiverExpression)

    fun tryExtractClassDescriptor(context: BindingContext): ClassDescriptor? =
        (tryExtractReceiver(context) as? ClassQualifier)?.descriptor

    fun tryExtractClassDescriptorFromAlias(context: BindingContext): ClassDescriptor? =
        (tryExtractReceiver(context) as? TypeAliasQualifier)?.classDescriptor

    fun extractReceiverTypeFrom(
        context: BindingContext,
        receiverExpression: KtExpression
    ): KotlinType? {
        return context.getType(receiverExpression)
            ?: tryExtractClassDescriptor(context)?.let { extractReceiverTypeFrom(it) }
            ?: tryExtractClassDescriptorFromAlias(context)?.let { extractReceiverTypeFrom(it) }
    }

    val expressionReceiver = receiverExpression?.let {
        val receiverType =
            extractReceiverTypeFrom(bindingContext, receiverExpression) ?: return emptyList()
        ExpressionReceiver.create(receiverExpression, receiverType, bindingContext)
    }

    val implicitReceiverValues = resolutionScope.getImplicitReceiversWithInstance(
        excludeShadowedByDslMarkers = languageVersionSettings.supportsFeature(LanguageFeature.DslMarkersSupport)
    ).map { it.value }

    val dataFlowInfo = bindingContext.getDataFlowInfoBefore(contextElement)

    val result = ArrayList<ReceiverType>()

    var receiverIndex = 0

    fun addReceiverType(receiverValue: ReceiverValue, implicit: Boolean) {
        val types = receiverValueTypes(
            receiverValue, dataFlowInfo, bindingContext, moduleDescriptor, stableSmartCastsOnly,
            resolutionFacade
        )

        types.mapTo(result) { type ->
            ReceiverType(
                type,
                receiverIndex,
                receiverValue.takeIf { implicit })
        }

        receiverIndex++
    }
    if (withImplicitReceiversWhenExplicitPresent || expressionReceiver == null) {
        implicitReceiverValues.forEach { addReceiverType(it, true) }
    }
    if (expressionReceiver != null) {
        addReceiverType(expressionReceiver, false)
    }
    return result
}


fun TypeConstructor.supertypesWithAny(): Collection<KotlinType> {
    val supertypes = supertypes
    val noSuperClass =
        supertypes.map { it.constructor.declarationDescriptor as? ClassDescriptor }.all {
            it == null || it.kind == ClassKind.INTERFACE
        }
    return if (noSuperClass) supertypes + builtIns.anyType else supertypes
}

@OptIn(FrontendInternals::class)
private fun receiverValueTypes(
    receiverValue: ReceiverValue,
    dataFlowInfo: DataFlowInfo,
    bindingContext: BindingContext,
    moduleDescriptor: ModuleDescriptor,
    stableSmartCastsOnly: Boolean,
    resolutionFacade: ResolutionFacade
): List<KotlinType> {
    val languageVersionSettings = resolutionFacade.getLanguageVersionSettings()
    val dataFlowValueFactory = resolutionFacade.getDataFlowValueFactory()
    val smartCastManager = resolutionFacade.frontendService<SmartCastManager>()
    val dataFlowValue =
        dataFlowValueFactory.createDataFlowValue(receiverValue, bindingContext, moduleDescriptor)
    return if (dataFlowValue.isStable || !stableSmartCastsOnly) { // we don't include smart cast receiver types for "unstable" receiver value to mark members grayed
        smartCastManager.getSmartCastVariantsWithLessSpecificExcluded(
            receiverValue,
            bindingContext,
            moduleDescriptor,
            dataFlowInfo,
            languageVersionSettings,
            dataFlowValueFactory
        )
    } else {
        listOf(receiverValue.type)
    }
}


sealed class CallTypeAndReceiver<TReceiver : KtElement?, out TCallType : CallType<TReceiver>>(
    val callType: TCallType,
    val receiver: TReceiver
) {
    object UNKNOWN : CallTypeAndReceiver<Nothing?, CallType.UNKNOWN>(CallType.UNKNOWN, null)
    object DEFAULT : CallTypeAndReceiver<Nothing?, CallType.DEFAULT>(CallType.DEFAULT, null)
    class DOT(receiver: KtExpression) :
        CallTypeAndReceiver<KtExpression, CallType.DOT>(CallType.DOT, receiver)

    class SAFE(receiver: KtExpression) :
        CallTypeAndReceiver<KtExpression, CallType.SAFE>(CallType.SAFE, receiver)

    class SUPER_MEMBERS(receiver: KtSuperExpression) :
        CallTypeAndReceiver<KtSuperExpression, CallType.SUPER_MEMBERS>(
            CallType.SUPER_MEMBERS, receiver
        )

    class INFIX(receiver: KtExpression) :
        CallTypeAndReceiver<KtExpression, CallType.INFIX>(CallType.INFIX, receiver)

    class OPERATOR(receiver: KtExpression) : CallTypeAndReceiver<KtExpression, CallType.OPERATOR>(
        CallType.OPERATOR, receiver
    )

    class CALLABLE_REFERENCE(receiver: KtExpression?) :
        CallTypeAndReceiver<KtExpression?, CallType.CALLABLE_REFERENCE>(
            CallType.CALLABLE_REFERENCE, receiver
        )

    class IMPORT_DIRECTIVE(receiver: KtExpression?) :
        CallTypeAndReceiver<KtExpression?, CallType.IMPORT_DIRECTIVE>(
            CallType.IMPORT_DIRECTIVE, receiver
        )

    class PACKAGE_DIRECTIVE(receiver: KtExpression?) :
        CallTypeAndReceiver<KtExpression?, CallType.PACKAGE_DIRECTIVE>(
            CallType.PACKAGE_DIRECTIVE,
            receiver
        )

    class TYPE(receiver: KtExpression?) :
        CallTypeAndReceiver<KtExpression?, CallType.TYPE>(CallType.TYPE, receiver)

    class DELEGATE(receiver: KtExpression?) : CallTypeAndReceiver<KtExpression?, CallType.DELEGATE>(
        CallType.DELEGATE, receiver
    )

    class ANNOTATION(receiver: KtExpression?) :
        CallTypeAndReceiver<KtExpression?, CallType.ANNOTATION>(
            CallType.ANNOTATION, receiver
        )

    companion object {
        fun detect(expression: KtSimpleNameExpression): CallTypeAndReceiver<*, *> {
            val parent = expression.parent
            if (parent is KtCallableReferenceExpression && expression == parent.callableReference) {
                return CALLABLE_REFERENCE(parent.receiverExpression)
            }

            val receiverExpression = expression.getReceiverExpression()

            if (expression.isImportDirectiveExpression()) {
                return IMPORT_DIRECTIVE(receiverExpression)
            }

            if (expression.isPackageDirectiveExpression()) {
                return PACKAGE_DIRECTIVE(receiverExpression)
            }

            if (parent is KtUserType) {
                val constructorCallee =
                    (parent.parent as? KtTypeReference)?.parent as? KtConstructorCalleeExpression
                if (constructorCallee != null && constructorCallee.parent is KtAnnotationEntry) {
                    return ANNOTATION(receiverExpression)
                }

                return TYPE(receiverExpression)
            }

            when (expression) {
                is KtOperationReferenceExpression -> {
                    if (receiverExpression == null) {
                        return UNKNOWN // incomplete code
                    }
                    return when (parent) {
                        is KtBinaryExpression -> {
                            if (parent.operationToken == KtTokens.IDENTIFIER)
                                INFIX(receiverExpression)
                            else
                                OPERATOR(receiverExpression)
                        }

                        is KtUnaryExpression -> OPERATOR(receiverExpression)

                        else -> error("Unknown parent for JetOperationReferenceExpression: $parent with text '${parent.text}'")
                    }
                }

                is KtNameReferenceExpression -> {
                    if (receiverExpression == null) {
                        return DEFAULT
                    }

                    if (receiverExpression is KtSuperExpression) {
                        return SUPER_MEMBERS(receiverExpression)
                    }

                    return when (parent) {
                        is KtCallExpression -> {
                            if ((parent.parent as KtQualifiedExpression).operationSign == KtTokens.SAFE_ACCESS)
                                SAFE(receiverExpression)
                            else
                                DOT(receiverExpression)
                        }

                        is KtQualifiedExpression -> {
                            if (parent.operationSign == KtTokens.SAFE_ACCESS)
                                SAFE(receiverExpression)
                            else
                                DOT(receiverExpression)
                        }

                        else -> error("Unknown parent for JetNameReferenceExpression with receiver: $parent")
                    }
                }

                else -> return UNKNOWN
            }
        }
    }
}
