package com.yumark.app.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yumark.app.R
import com.yumark.app.domain.model.Folder
import com.yumark.app.domain.repository.FolderRepository

/**
 * 文件夹的显示名。
 *
 * 只为一个文件夹存在：导入库。它是首次导入时惰性创建的，创建时 name 直接取了
 * [FolderRepository.IMPORT_LIBRARY_FOLDER_NAME] —— 一个中文常量，写进了 Room。于是英文语区的
 * 用户会在侧栏、移动对话框、导入位置选择器里看到「导入库」四个汉字。资源里早就备好了
 * `R.string.import_library`（导入库 / Import Library），只是从来没接上。
 *
 * 不能改常量本身：那个名字已经在老用户的库里了，改常量既不会追改数据库，还得为此写一次迁移；
 * 而文件夹的身份从来是 [FolderRepository.IMPORT_LIBRARY_FOLDER_ID]，名字只是显示用的。
 * 所以做法是显示时替换。
 *
 * 只在名字仍是自动创建时的原值时才替换：用户手动重命名过就显示他起的名字。少了这个判断，
 * 用户的重命名会被静默忽略——那比不本地化更糟。
 */
fun Folder.displayName(importLibraryLabel: String): String =
    if (id == FolderRepository.IMPORT_LIBRARY_FOLDER_ID &&
        name == FolderRepository.IMPORT_LIBRARY_FOLDER_NAME
    ) {
        importLibraryLabel
    } else {
        name
    }

/**
 * 组合期用的重载。
 *
 * `remember {}` 的 block 和普通循环体不是 @Composable，读不了 stringResource，所以保留上面那个
 * 收 String 的重载：调用方在组合期把文案取好（顺带进 remember 的 key，切换系统语言会重建），
 * 再传进去。
 */
@Composable
fun Folder.displayName(): String = displayName(stringResource(R.string.import_library))
