# Contributing to YuMark

Thank you for your interest in contributing to YuMark!

## Development Setup

1. **Prerequisites**
   - Android Studio Hedgehog (2023.1.1) or later
   - JDK 17
   - Android SDK 34
   - Git

2. **Clone and Build**
   ```bash
   git clone https://github.com/yourusername/yumark.git
   cd yumark
   bash download-js-libs.sh
   ./gradlew build
   ```

3. **Run Tests**
   ```bash
   ./gradlew test
   ```

## Project Architecture

YuMark follows **Clean Architecture** with three layers:

```
┌─────────────────────────────────────┐
│         Presentation (UI)           │  Jetpack Compose + ViewModels
├─────────────────────────────────────┤
│       Domain (Business Logic)       │  UseCases + Models + Repositories
├─────────────────────────────────────┤
│         Data (Data Sources)         │  Room + DataStore + Files
└─────────────────────────────────────┘
```

### Key Principles

- **Dependency Rule**: Inner layers don't depend on outer layers
- **MVVM**: UI observes ViewModels via StateFlow
- **Repository Pattern**: Abstract data sources behind interfaces
- **Use Cases**: Encapsulate single business operations

## Coding Standards

### Kotlin Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `val` over `var` when possible
- Prefer `?.` and `?:` over explicit null checks
- Use sealed classes for state modeling

### Compose Guidelines

- Keep Composables small and focused
- Extract reusable UI into separate functions
- Use `remember` for state that survives recomposition
- Hoist state when needed across multiple Composables

### Testing

- Write unit tests for UseCases and Repositories
- Use MockK for mocking dependencies
- Follow Arrange-Act-Assert pattern
- Aim for >70% code coverage

## Pull Request Process

1. **Fork** the repository
2. **Create a feature branch**: `git checkout -b feature/your-feature`
3. **Commit changes**: Use clear, descriptive commit messages
4. **Write tests**: Add/update tests for your changes
5. **Push**: `git push origin feature/your-feature`
6. **Open a PR**: Describe what and why you changed

### PR Checklist

- [ ] Code builds without errors
- [ ] Tests pass locally
- [ ] New code has tests
- [ ] UI changes include screenshots
- [ ] Documentation updated if needed

## Areas for Contribution

- [ ] Markdown extensions (footnotes, task lists, etc.)
- [ ] Theme customization (custom fonts, colors)
- [ ] Export to PDF using Android's print framework
- [ ] Cloud sync (Google Drive, Dropbox)
- [ ] Collaborative editing
- [ ] Plugin system

## Questions?

Open an issue or discussion on GitHub!
