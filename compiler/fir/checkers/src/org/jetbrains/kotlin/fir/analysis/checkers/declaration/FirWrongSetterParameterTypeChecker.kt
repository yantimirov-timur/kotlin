/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.extended.report
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.WRONG_SETTER_PARAMETER_TYPE
import org.jetbrains.kotlin.fir.types.coneType


object FirWrongSetterParameterTypeChecker : FirPropertyChecker() {
    override fun check(declaration: FirProperty, context: CheckerContext, reporter: DiagnosticReporter) {
        val setter = declaration.setter ?: return
        val valueParameter = setter.valueParameters.first()
        val valueSource = valueParameter.returnTypeRef.source
        val valueType = valueParameter.returnTypeRef.coneType
        val propertyType = declaration.returnTypeRef.coneType

        if (valueType != propertyType) {
            reporter.report(valueSource, WRONG_SETTER_PARAMETER_TYPE)
        }
    }
}


