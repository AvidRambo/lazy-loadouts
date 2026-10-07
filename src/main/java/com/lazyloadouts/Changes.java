package com.lazyloadouts;

import java.util.List;
import lombok.Data;

/**
 * The setups whose best item for a slot is different in this version of the data from the last
 */
@Data
class Changes
{
	private String version;
	private List<Change> changes;

	@Data
	static class Change
	{
		private String activity;
		private String loadout;
		private String slot;
		/**
		 * The item that is now the best
		 */
		private int item;
	}
}
