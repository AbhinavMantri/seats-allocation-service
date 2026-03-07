package com.example.seats_allocation_service;

import com.example.seats_allocation_service.service.EventInventoryService;
import com.example.seats_allocation_service.service.EventSeatService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=" +
				"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
				"org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration," +
				"org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
})
class SeatsAllocationServiceApplicationTests {

	@MockitoBean
	EventInventoryService eventInventoryService;

	@MockitoBean
	EventSeatService eventSeatService;

	@Test
	void contextLoads() {
	}

}
