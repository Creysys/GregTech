package gregtech.api.recipes;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import gnu.trove.impl.unmodifiable.TUnmodifiableObjectIntMap;
import gnu.trove.map.TObjectIntMap;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.util.GTUtility;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.apache.commons.lang3.Validate;

import java.util.*;

/**
 * Class that represent machine recipe.<p>
 *
 * Recipes are created using {@link RecipeBuilder} or its subclasses in builder-alike pattern. To get RecipeBuilder use {@link RecipeMap#recipeBuilder()}.<p>
 *
 * Example:
 * 		RecipeMap.POLARIZER_RECIPES.recipeBuilder().inputs(new ItemStack(Items.APPLE)).outputs(new ItemStack(Items.GOLDEN_APPLE)).duration(256).EUt(480).buildAndRegister();<p>
 * 	This will create and register Polarizer recipe with Apple as input and Golden apple as output, duration - 256 ticks and energy consumption of 480 EU/t.<p>
 *	To get example for particular RecipeMap see {@link RecipeMap}<p>
 *
 * Recipes are immutable.
 */
//TODO CraftTweaker support
public class Recipe {

    public static int getMaxChancedValue() {
        return 10000;
    }

    public static String formatChanceValue(int outputChance) {
        return String.format("%.2f", outputChance / (getMaxChancedValue() * 1.0) * 100);
    }

	private final List<CountableIngredient> inputs;
	private final NonNullList<ItemStack> outputs;

	/**
	 * A chance of 10000 equals 100%
	 */
	private final TObjectIntMap<ItemStack> chancedOutputs;

	private final List<FluidStack> fluidInputs;
	private final List<FluidStack> fluidOutputs;

	private final int duration;

	/**
	 * if > 0 means EU/t consumed, if < 0 - produced
	 */
	private final int EUt;

	/**
	 * If this Recipe is hidden from JEI
	 */
	private final boolean hidden;

	/**
	 * If this Recipe can be stored inside a Machine in order to make Recipe searching more efficient
	 * by trying the previously used Recipe first. In case you have a Recipe Map overriding things
	 * and returning one time use Recipes, you have to set this to false.
	 */
	private final boolean canBeBuffered;
	/**
	 * If this Recipe needs the Output Slots to be completely empty. Needed in case you have randomised Outputs
	 */
	private final boolean needsEmptyOutput;

    private final Map<String, Object> recipeProperties;

	public Recipe(List<CountableIngredient> inputs, List<ItemStack> outputs, TObjectIntMap<ItemStack> chancedOutputs,
                     List<FluidStack> fluidInputs, List<FluidStack> fluidOutputs,
                     Map<String, Object> recipeProperties, int duration, int EUt, boolean hidden, boolean canBeBuffered, boolean needsEmptyOutput) {
        this.recipeProperties = ImmutableMap.copyOf(recipeProperties);
        this.inputs = NonNullList.create();
		this.inputs.addAll(inputs);
		this.outputs = NonNullList.create();
		this.outputs.addAll(outputs);
		this.chancedOutputs = new TUnmodifiableObjectIntMap<>(chancedOutputs);
		this.fluidInputs = ImmutableList.copyOf(fluidInputs);
		this.fluidOutputs = ImmutableList.copyOf(fluidOutputs);
		this.duration = duration;
		this.EUt = EUt;
		this.hidden = hidden;
		this.canBeBuffered = canBeBuffered;
		this.needsEmptyOutput = needsEmptyOutput;
	}

	public boolean matches(boolean consumeIfSuccessful, boolean dontCheckStackSizes, IItemHandlerModifiable inputs, IMultipleTankHandler fluidInputs) {
		return matches(consumeIfSuccessful, dontCheckStackSizes, GTUtility.itemHandlerToList(inputs), GTUtility.fluidHandlerToList(fluidInputs));
	}

	public boolean matches(boolean consumeIfSuccessful, boolean dontCheckStackSizes, List<ItemStack> inputs, List<FluidStack> fluidInputs) {
	    int[] fluidAmountInTank = new int[fluidInputs.size()];
	    int[] itemAmountInSlot = new int[inputs.size()];

        for(int i = 0; i < fluidAmountInTank.length; i++) {
            FluidStack fluidInTank = fluidInputs.get(i);
            fluidAmountInTank[i] = fluidInTank == null ? 0 : fluidInTank.amount;
        }
        for(int i = 0; i < itemAmountInSlot.length; i++) {
            ItemStack itemInSlot = inputs.get(i);
            itemAmountInSlot[i] = itemInSlot.isEmpty() ? 0 : itemInSlot.getCount();
        }

        for (FluidStack fluid : this.fluidInputs) {
            int fluidAmount = fluid.amount;
            for (int i = 0; i < fluidInputs.size(); i++) {
                FluidStack tankFluid = fluidInputs.get(i);
                if (tankFluid == null || !tankFluid.isFluidEqual(fluid))
                    continue;
                int fluidAmountToConsume = Math.min(fluidAmountInTank[i], fluidAmount);
                fluidAmount -= fluidAmountToConsume;
                fluidAmountInTank[i] -= fluidAmountToConsume;
                if (fluidAmount == 0) break;
            }
            if(fluidAmount > 0)
                return false;
        }

        for(CountableIngredient ingredient : this.inputs) {
            int ingredientAmount = ingredient.getCount();
            boolean isNotConsumed = false;
            if(ingredientAmount == 0) {
                ingredientAmount = 1;
                isNotConsumed = true;
            }
            for (int i = 0; i < inputs.size(); i++) {
                ItemStack inputStack = inputs.get(i);
                if (inputStack.isEmpty() || !ingredient.getIngredient().apply(inputStack))
                    continue;
                int itemAmountToConsume = Math.min(itemAmountInSlot[i], ingredientAmount);
                ingredientAmount -= itemAmountToConsume;
                if(!isNotConsumed) itemAmountInSlot[i] -= itemAmountToConsume;
                if(ingredientAmount == 0) break;
            }
            if(ingredientAmount > 0)
                return false;
        }

        if(consumeIfSuccessful) {
            for(int i = 0; i < fluidAmountInTank.length; i++) {
                FluidStack fluidStack = fluidInputs.get(i);
                int fluidAmount = fluidAmountInTank[i];
                if(fluidStack == null || fluidStack.amount == fluidAmount)
                    continue;
                fluidStack.amount = fluidAmount;
                if(fluidStack.amount == 0)
                    fluidInputs.set(i, null);
            }
            for(int i = 0; i < itemAmountInSlot.length; i++) {
                ItemStack itemInSlot = inputs.get(i);
                int itemAmount = itemAmountInSlot[i];
                if(itemInSlot.isEmpty() || itemInSlot.getCount() == itemAmount)
                    continue;
                itemInSlot.setCount(itemAmountInSlot[i]);
            }
        }

        return true;
	}

	///////////////////
	//    Getters    //
	///////////////////

	public List<CountableIngredient> getInputs() {
		return inputs;
	}

	public NonNullList<ItemStack> getOutputs() {
		return outputs;
	}

	public List<ItemStack> getResultItemOutputs(Random random) {
        ArrayList<ItemStack> outputs = new ArrayList<>(GTUtility.copyStackList(getOutputs()));
        TObjectIntMap<ItemStack> chancedOutputsMap = getChancedOutputs();
	    for(ItemStack chancedOutput : chancedOutputsMap.keySet()) {
	        int outputChance = chancedOutputsMap.get(chancedOutput);
	        if(random.nextInt(Recipe.getMaxChancedValue()) <= outputChance)
	            outputs.add(chancedOutput.copy());
        }
	    return outputs;
    }

	public TObjectIntMap<ItemStack> getChancedOutputs() {
		return chancedOutputs;
	}

	public List<FluidStack> getFluidInputs() {
		return fluidInputs;
	}

	public boolean hasInputFluid(Fluid fluid) {
	    for(FluidStack fluidStack : fluidInputs) {
	        if(fluidStack.getFluid() == fluid) {
	            return true;
            }
        }
        return false;
    }

	public List<FluidStack> getFluidOutputs() {
		return fluidOutputs;
	}

	public int getDuration() {
		return duration;
	}

	public int getEUt() {
		return EUt;
	}

	public boolean isHidden() {
		return hidden;
	}

	public boolean canBeBuffered() {
		return canBeBuffered;
	}

	public boolean needsEmptyOutput() {
		return needsEmptyOutput;
	}

	public Set<String> getPropertyKeys() {
	    return recipeProperties.keySet();
    }

	public boolean getBooleanProperty(String key) {
        Validate.notNull(key);
        Object o = this.recipeProperties.get(key);
        if (!(o instanceof Boolean)) {
            throw new IllegalArgumentException();
        }
        return (boolean) o;
    }

    public int getIntegerProperty(String key) {
        Validate.notNull(key);
        Object o = this.recipeProperties.get(key);
        if (!(o instanceof Integer)) {
            throw new IllegalArgumentException();
        }
        return (int) o;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key) {
        Validate.notNull(key);
        Object o = this.recipeProperties.get(key);
        if (o == null) {
            throw new IllegalArgumentException();
        }
        return (T) o;
    }

	public String getStringProperty(String key) {
        Validate.notNull(key);
        Object o = this.recipeProperties.get(key);
        if (!(o instanceof String)) {
            throw new IllegalArgumentException();
        }
        return (String) o;
    }

	public static class AssemblyLineRecipe {

		private final ItemStack researchItem;
		private final int researchTime;

		private final List<ItemStack> inputs;
		private final List<FluidStack> fluidInputs;
		private final ItemStack output;

		private final int duration;
		private final int EUt;

		public AssemblyLineRecipe(ItemStack researchItem, int researchTime, List<ItemStack> inputs, List<FluidStack> fluidInputs, ItemStack output, int duration, int EUt) {
			this.researchItem = researchItem.copy();
			this.researchTime = researchTime;
			this.inputs = ImmutableList.copyOf(GTUtility.copyStackList(inputs));
			this.fluidInputs = ImmutableList.copyOf(GTUtility.copyFluidList(fluidInputs));
			this.output = output;
			this.duration = duration;
			this.EUt = EUt;
		}

		public ItemStack getResearchItem() {
			return researchItem;
		}

		public int getResearchTime() {
			return researchTime;
		}

		public List<ItemStack> getInputs() {
			return inputs;
		}

		public List<FluidStack> getFluidInputs() {
			return fluidInputs;
		}

		public ItemStack getOutput() {
			return output;
		}

		public int getDuration() {
			return duration;
		}

        public int getEUt() {
            return EUt;
        }
    }

    public static class PrimitiveBlastFurnaceRecipe {

	    private final Ingredient input;
        private final ItemStack output;

        private final int duration;
        private final int fuelAmount;

        public PrimitiveBlastFurnaceRecipe(Ingredient input, ItemStack output, int duration, int fuelAmount) {
            this.input = input;
            this.output = output;
            this.duration = duration;
            this.fuelAmount = fuelAmount;
        }

        public Ingredient getInput() {
            return input;
        }

        public int getDuration() {
            return duration;
        }

        public int getFuelAmount() {
            return fuelAmount;
        }

        public ItemStack getOutput() {
            return output;
        }
    }
}
