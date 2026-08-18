package com.kanicream.flowlens.analysis

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement

/**
 * Where a declaration lives, for use in a symbol key when the language cannot
 * supply a project-unique name of its own.
 *
 * A key has to be unique within a project, because it is what cycle detection
 * compares along a path and what a Flow Pin is stored under. A key built from a
 * bare file name is not: two `Foo.java` in different packages, or two Go
 * `package main` directories, produce the same key and the analyzer then reports
 * a cycle between two unrelated functions.
 */
object SymbolQualifier {

    /** The declaration's file, project-relative, or its name when it is outside. */
    fun fileQualifier(declaration: PsiElement): String {
        val file = declaration.containingFile?.virtualFile ?: return "?"
        val base = declaration.project.guessProjectDir()
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
    }

    /** The declaration's directory, project-relative. A Go package is its directory. */
    fun directoryQualifier(declaration: PsiElement): String? {
        val file = declaration.containingFile?.virtualFile ?: return null
        val parent = file.parent ?: return null
        val base = declaration.project.guessProjectDir() ?: return parent.name
        return VfsUtilCore.getRelativePath(parent, base) ?: parent.path
    }
}
