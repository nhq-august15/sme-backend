package sme.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import sme.backend.dto.ai.*;
import sme.backend.entity.Product;
import sme.backend.entity.Category;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.CategoryRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;

@Configuration
public class CustomerAiConfig {

    @Bean("customerChatClient")
    public ChatClient customerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        Bạn là trợ lý tư vấn khách hàng của nhà sách trực tuyến.
                        Nhiệm vụ của bạn:
                        - Giúp khách hàng tìm kiếm sách theo tên, tác giả, thể loại.
                        - Cung cấp thông tin tóm tắt về sách và giá bán nếu khách hàng yêu cầu.
                        - Trả lời bằng tiếng Việt, giọng điệu nhiệt tình, lịch sự và thân thiện.
                        - Gợi ý thêm các đầu sách tương tự nếu thấy phù hợp.
                        
                        NGUYÊN TẮC BẢO MẬT:
                        - TUYỆT ĐỐI KHÔNG chia sẻ thông tin về giá nhập (macPrice), lợi nhuận, chi phí quản lý, tồn kho thực tế ở các chi nhánh, thông tin người dùng khác hay các số liệu nhạy cảm của cửa hàng.
                        - Chỉ sử dụng các công cụ tìm kiếm được cung cấp để lấy thông tin sản phẩm, danh mục. Không tự ý bịa đặt tên sách hay thông tin không có trong cửa hàng.
                        - Nếu không tìm thấy sách khách yêu cầu, hãy xin lỗi và giới thiệu các chủ đề khác.
                        """)
                .defaultFunctions("searchProductsFunction", "getCategoriesFunction", "getTopSellingProductsFunction")
                .build();
    }

    @Bean
    @Description("Tìm kiếm sách, sản phẩm trong cửa hàng theo từ khóa (tên sách, thể loại, tác giả)")
    public Function<ProductSearchRequest, ProductSearchResponse> searchProductsFunction(ProductRepository productRepository) {
        return request -> {
            String keyword = request.keyword() != null ? request.keyword() : "";
            Page<Product> page = productRepository.searchByKeyword(keyword, PageRequest.of(0, 10));
            List<ProductSearchResponse.ProductDto> products = page.getContent().stream()
                    .map(p -> new ProductSearchResponse.ProductDto(
                            p.getName(),
                            p.getRetailPrice(),
                            p.getDescription() != null && p.getDescription().length() > 200 
                                    ? p.getDescription().substring(0, 200) + "..." 
                                    : p.getDescription()
                    ))
                    .toList();
            return new ProductSearchResponse(products);
        };
    }

    @Bean
    @Description("Lấy danh sách tất cả các thể loại sách (danh mục) đang có bán tại cửa hàng")
    public Function<CategorySearchRequest, CategorySearchResponse> getCategoriesFunction(CategoryRepository categoryRepository) {
        return request -> {
            List<String> categories = categoryRepository.findByIsActiveTrueOrderBySortOrder().stream()
                    .map(Category::getName)
                    .toList();
            return new CategorySearchResponse(categories);
        };
    }

    @Bean
    @Description("Lấy danh sách các sách bán chạy nhất tại cửa hàng (Top selling books)")
    public Function<TopSellingProductsRequest, ProductSearchResponse> getTopSellingProductsFunction(ProductRepository productRepository) {
        return request -> {
            int limit = (request.limit() != null && request.limit() > 0) ? request.limit() : 5;
            // Get best selling products in the last 30 days
            Instant fromDate = Instant.now().minus(30, ChronoUnit.DAYS);
            Instant toDate = Instant.now();
            List<Map<String, Object>> topProducts = productRepository.findTopSellingProducts(null, fromDate, toDate, limit, null);
            
            List<ProductSearchResponse.ProductDto> products = topProducts.stream()
                    .map(map -> {
                        String name = (String) map.get("name");
                        return new ProductSearchResponse.ProductDto(name, BigDecimal.ZERO, "Sách bán chạy");
                    })
                    .toList();
            return new ProductSearchResponse(products);
        };
    }
}
