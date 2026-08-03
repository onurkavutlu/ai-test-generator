// Page Object Model (POM) classes

public class SearchResultPage {
    @FindBy(xpath = "//h3")
    private List<WebElement> searchResults;

    public boolean isSearchResultPresent(String keyword) {
        return searchResults.stream().anyMatch(result -> result.getText().contains(keyword));
    }
}