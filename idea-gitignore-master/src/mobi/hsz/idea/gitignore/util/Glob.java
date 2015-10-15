/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.util;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.util.containers.ContainerUtil;
import mobi.hsz.idea.gitignore.IgnoreBundle;
import mobi.hsz.idea.gitignore.psi.IgnoreEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Glob util class that prepares glob statements or searches for content using glob rules.
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 0.5
 */
public class Glob {
    /** Cache map that holds processed regex statements to the glob rules. */
    private static final HashMap<String, String> cache = ContainerUtil.newHashMap();

    /** Private constructor to prevent creating {@link Glob} instance. */
    private Glob() {
    }

    /**
     * Finds for {@link VirtualFile} list using glob rule in given root directory.
     *
     * @param root root directory
     * @param entry ignore entry
     * @return search result
     */
    public static List<VirtualFile> find(@NotNull final VirtualFile root, @NotNull IgnoreEntry entry) {
        return find(root, entry, false);
    }

    /**
     * Finds for {@link VirtualFile} list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entry         ignore entry
     * @param includeNested attach children to the search result
     * @return search result
     */
    public static List<VirtualFile> find(@NotNull final VirtualFile root, @NotNull IgnoreEntry entry, final boolean includeNested) {
        Pattern pattern = createPattern(entry);
        if (pattern == null) {
            return Collections.emptyList();
        }

        final List<VirtualFile> files = ContainerUtil.newArrayList();

        VirtualFileVisitor<Pattern> visitor = new VirtualFileVisitor<Pattern>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                boolean matches = false;
                Pattern pattern = getCurrentValue();
                String path = Utils.getRelativePath(root, file);

                if (path == null || Utils.isVcsDirectory(file)) {
                    return false;
                }

                if (pattern == null || pattern.matcher(path).matches()) {
                    matches = true;
                    files.add(file);
                }

                setValueForChildren(includeNested && matches ? null : pattern);
                return true;
            }
        };
        visitor.setValueForChildren(pattern);
        VfsUtil.visitChildrenRecursively(root, visitor);

        return files;
    }

    /**
     * Finds for {@link VirtualFile} paths list using glob rule in given root directory.
     *
     * @param root  root directory
     * @param entry ignore entry
     * @return search result
     */
    public static List<String> findAsPaths(@NotNull VirtualFile root, @NotNull IgnoreEntry entry) {
        return findAsPaths(root, entry, false);
    }

    /**
     * Finds for {@link VirtualFile} paths list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entry         ignore entry
     * @param includeNested attach children to the search result
     * @return search result
     */
    public static List<String> findAsPaths(@NotNull VirtualFile root, @NotNull IgnoreEntry entry, boolean includeNested) {
        List<String> list = ContainerUtil.newArrayList();
        List<VirtualFile> files = find(root, entry, includeNested);
        for (VirtualFile file : files) {
            list.add(Utils.getRelativePath(root, file));
        }
        return list;
    }

    /**
     * Creates regex {@link Pattern} using glob rule.
     *
     * @param rule rule value
     * @param syntax rule syntax
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull String rule, @NotNull IgnoreBundle.Syntax syntax) {
        return createPattern(rule, syntax, false);
    }

    /**
     * Creates regex {@link Pattern} using glob rule.
     *
     * @param rule rule value
     * @param syntax rule syntax
     * @param acceptChildren Matches directory children
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull String rule, @NotNull IgnoreBundle.Syntax syntax, boolean acceptChildren) {
        final String regex = syntax.equals(IgnoreBundle.Syntax.GLOB) ? createRegex(rule, acceptChildren) : rule;
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Creates regex {@link Pattern} using {@link IgnoreEntry}.
     *
     * @param entry {@link IgnoreEntry}
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull IgnoreEntry entry) {
        return createPattern(entry, false);
    }

    /**
     * Creates regex {@link Pattern} using {@link IgnoreEntry}.
     *
     * @param entry {@link IgnoreEntry}
     * @param acceptChildren Matches directory children
     * @return regex {@link Pattern}
     */
    @Nullable
    public static Pattern createPattern(@NotNull IgnoreEntry entry, boolean acceptChildren) {
        return createPattern(entry.getValue(), entry.getSyntax(), acceptChildren);
    }

    /**
     * Creates regex {@link String} using glob rule.
     *
     * @param glob rule
     * @param acceptChildren Matches directory children
     * @return regex {@link String}
     */
    @NotNull
    public static String createRegex(@NotNull String glob, boolean acceptChildren) {
        glob = glob.trim();
        String cached = cache.get(glob);
        if (cached != null) {
            return cached;
        }

        StringBuilder sb = new StringBuilder("^");
        boolean escape = false, star = false, doubleStar = false, bracket = false;
        int beginIndex = 0;

        if (StringUtil.startsWith(glob, "**")) {
            sb.append("([^/]*?/)*");
            beginIndex = 2;
            doubleStar = true;
        } else if (StringUtil.startsWith(glob, "*/")) {
            sb.append("[^/]*");
            beginIndex = 1;
            star = true;
        } else if (StringUtil.equals("*", glob)) {
            sb.append(".*");
        } else if (StringUtil.startsWithChar(glob, '*')) {
            sb.append(".*?");
        } else if (!StringUtil.containsChar(glob, '/')) {
            sb.append("([^/]*?/)*");
        } else if (StringUtil.startsWithChar(glob, '/')) {
            beginIndex = 1;
        }

        char[] chars = glob.substring(beginIndex).toCharArray();

        for (char ch : chars) {
            if (bracket && ch != ']') {
                sb.append(ch);
                continue;
            } else if (doubleStar) {
                doubleStar = false;
                if (ch == '/') {
                    sb.append("([^/]*/)*?");
                    continue;
                } else {
                    sb.append("[^/]*?");
                }
            }

            if (ch == '*') {
                if (escape) {
                    sb.append("\\*");
                    escape = false;
                    star = false;
                } else if (star) {
                    char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : '\0';
                    if (prev == '\0' || prev == '^' || prev == '/') {
                        doubleStar = true;
                    } else {
                        sb.append("[^/]*?");
                    }
                    star = false;
                } else {
                    star = true;
                }
                continue;
            } else if (star) {
                sb.append("[^/]*?");
                star = false;
            }

            switch (ch) {

                case '\\':
                    if (escape) {
                        sb.append("\\\\");
                        escape = false;
                    } else {
                        escape = true;
                    }
                    break;

                case '?':
                    if (escape) {
                        sb.append("\\?");
                        escape = false;
                    } else {
                        sb.append('.');
                    }
                    break;

                case '[':
                    if (escape) {
                        sb.append('\\');
                        escape = false;
                    } else {
                        bracket = true;
                    }
                    sb.append(ch);
                    break;

                case ']':
                    if (!bracket) {
                        sb.append('\\');
                    }
                    sb.append(ch);
                    bracket = false;
                    escape = false;
                    break;

                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    sb.append('\\');
                    sb.append(ch);
                    escape = false;
                    break;

                default:
                    escape = false;
                    sb.append(ch);

            }
        }

        if (star || doubleStar) {
            if (StringUtil.endsWithChar(sb, '/')) {
                sb.append(acceptChildren ? ".+" : "[^/]+");
            } else {
                sb.append("[^/]*");
            }
        } else {
            if (StringUtil.endsWithChar(sb, '/')) {
                sb.setLength(sb.length() - 1);
            }
            sb.append(acceptChildren ? "(/.*)?" : "/?");
        }

        sb.append('$');

        cache.put(glob, sb.toString());

        return sb.toString();
    }
}
